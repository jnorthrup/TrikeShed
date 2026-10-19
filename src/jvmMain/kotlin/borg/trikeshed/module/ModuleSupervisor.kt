package borg.trikeshed.module

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.net.URLClassLoader
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

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
    private class Retirement(val handle: ModuleHandle, val loader: URLClassLoader?) : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<Retirement>
        val completion = CompletableDeferred<Result<Unit>>()
    }
    private val retiring = HashMap<String, Retirement>()

    suspend fun attach(fqcn: String): ModuleHandle = mutex.withLock {
        val (cls, loader) = loadClass(fqcn)
        val module = cls.getDeclaredConstructor().newInstance() as? ForgeModule
            ?: throw IllegalArgumentException("$fqcn is not a ForgeModule")
        if (handles.containsKey(module.id) || retiring.containsKey(module.id)) {
            loader?.close()
            throw IllegalArgumentException("module '${module.id}' already attached or draining")
        }
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
        require(!handles.containsKey(module.id) && !retiring.containsKey(module.id)) { "module '${module.id}' already attached or draining" }
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
        var owns = false
        val retirement = mutex.withLock {
            retiring[moduleId] ?: handles.remove(moduleId)?.let { handle ->
                Retirement(handle, loaders.remove(moduleId)).also { retiring[moduleId] = it; owns = true }
            }
        } ?: return false
        if (!owns) check(currentCoroutineContext()[Retirement]?.completion?.isCompleted != false) {
            "A module retirement cannot join another active retirement"
        }
        if (owns) withContext(NonCancellable + retirement) {
            val failures = mutableListOf<Throwable>()
            suspend fun attempt(action: suspend () -> Unit) {
                try { action() } catch (failure: Throwable) { failures.add(failure) }
            }
            var released = emptyList<String>()
            attempt { retirement.handle.drain() }
            attempt { released = ctx.routes.release(moduleId) }
            attempt { retirement.handle.close() }
            attempt { retirement.loader?.close() }
            attempt {
                if (failures.isEmpty()) receipt("module/detached", moduleId, mapOf("routesReleased" to released))
                else receipt("module/detach-failed", moduleId, mapOf("routesReleased" to released,
                    "error" to (failures[0].message ?: failures[0].toString())))
            }
            val result = if (failures.isEmpty()) Result.success(Unit) else Result.failure(
                IllegalStateException("module '$moduleId' detach failed", failures[0]).also { failure ->
                    failures.drop(1).forEach(failure::addSuppressed)
                })
            mutex.withLock {
                if (retiring[moduleId] === retirement) retiring.remove(moduleId)
                retirement.completion.complete(result)
            }
        }
        retirement.completion.await().getOrThrow()
        return true
    }

    fun describeAll(): List<Map<String, Any?>> = handles.values.map {
        mapOf("id" to it.id) + it.describe()
    }

    suspend fun drainAll() {
        withContext(NonCancellable) {
            val ids = mutex.withLock { (handles.keys + retiring.keys).distinct() }
            val failures = mutableListOf<Throwable>()
            for (id in ids) try { detach(id) } catch (failure: Throwable) { failures.add(failure) }
            if (failures.isNotEmpty()) throw IllegalStateException("module drain failed", failures[0]).also { failure ->
                failures.drop(1).forEach(failure::addSuppressed)
            }
        }
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
