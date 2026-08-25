package borg.trikeshed.module

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.net.URLClassLoader

/**
 * ModuleSupervisor — owns the proxy-ctor lifecycle. Attach is `Class.forName`
 * against the app loader first, then a fresh URLClassLoader over
 * build/live/classes — the same tree hotswapFeed refreshes — so a module class
 * that did not exist at boot can be compiled, land in build/live, and attach
 * without a daemon bounce. Upgrades are detach → attach (fresh instance, fresh
 * loader when needed); that is only safe when the module's state is
 * WAL-rebuildable, which is the ForgeModule author's contract.
 *
 * Every attach/detach lands a receipt through [receipt] (daemon wires it to the
 * blackboard ledger) — module topology is auditable history, not vibes.
 */
class ModuleSupervisor(
    private val ctx: ModuleContext,
    private val liveClassesDir: File? = null,
    private val receipt: (event: String, moduleId: String, detail: Map<String, Any?>) -> Unit = { _, _, _ -> },
) {
    private val mutex = Mutex()
    private val handles = LinkedHashMap<String, ModuleHandle>()
    private val loaders = HashMap<String, URLClassLoader>()

    suspend fun attach(fqcn: String): ModuleHandle = mutex.withLock {
        val (cls, loader) = loadClass(fqcn)
        val module = cls.getDeclaredConstructor().newInstance() as? ForgeModule
            ?: throw IllegalArgumentException("$fqcn is not a ForgeModule")
        require(!handles.containsKey(module.id)) { "module '${module.id}' already attached" }
        val handle = try {
            module.open(ctx)
        } catch (e: Throwable) {
            ctx.routes.release(module.id)   // half-opened claims must not linger
            loader?.close()
            receipt("module/attach-failed", module.id, mapOf("class" to fqcn, "error" to (e.message ?: e.toString())))
            throw e
        }
        handles[module.id] = handle
        if (loader != null) loaders[module.id] = loader
        receipt("module/attached", module.id, mapOf("class" to fqcn, "routes" to ctx.routes.paths().filterValues { it == module.id }.keys.toList()))
        handle
    }

    /** Pre-built instance path (boot-time defaults like KanbanModule — no reflection round-trip). */
    suspend fun attach(module: ForgeModule): ModuleHandle = mutex.withLock {
        require(!handles.containsKey(module.id)) { "module '${module.id}' already attached" }
        val handle = try {
            module.open(ctx)
        } catch (e: Throwable) {
            ctx.routes.release(module.id)
            receipt("module/attach-failed", module.id, mapOf("class" to module::class.java.name, "error" to (e.message ?: e.toString())))
            throw e
        }
        handles[module.id] = handle
        receipt("module/attached", module.id, mapOf("class" to module::class.java.name))
        handle
    }

    suspend fun detach(moduleId: String): Boolean {
        val handle = mutex.withLock { handles.remove(moduleId) } ?: return false
        runCatching { handle.drain() }
        val released = ctx.routes.release(moduleId)
        runCatching { handle.close() }
        mutex.withLock { loaders.remove(moduleId) }?.let { runCatching { it.close() } }
        receipt("module/detached", moduleId, mapOf("routesReleased" to released))
        return true
    }

    fun describeAll(): List<Map<String, Any?>> = handles.values.map {
        mapOf("id" to it.id) + it.describe()
    }

    suspend fun drainAll() {
        val ids = mutex.withLock { handles.keys.toList() }
        for (id in ids) detach(id)
    }

    private fun loadClass(fqcn: String): Pair<Class<*>, URLClassLoader?> {
        runCatching { Class.forName(fqcn) }.getOrNull()?.let { return it to null }
        val live = liveClassesDir
        require(live != null && live.isDirectory) { "class $fqcn not on app classpath and no live classes dir" }
        val loader = URLClassLoader(arrayOf(live.toURI().toURL()), ModuleSupervisor::class.java.classLoader)
        return try {
            Class.forName(fqcn, true, loader) to loader
        } catch (e: ClassNotFoundException) {
            loader.close()
            throw IllegalArgumentException("class $fqcn not found on app classpath or in $live", e)
        }
    }
}
