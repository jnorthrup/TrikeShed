package borg.trikeshed.hermes

import borg.trikeshed.parse.jsonOf
import borg.trikeshed.parse.reify
import borg.trikeshed.parse.reifyMap

import borg.trikeshed.cas.LineCas
import borg.trikeshed.btrfs.BtrfsWorldStore
import borg.trikeshed.collections.LineAperture
import borg.trikeshed.graal.ConfixBlackboard
import borg.trikeshed.graal.subvm.Budget
import borg.trikeshed.lib.*
import borg.trikeshed.graal.subvm.GraalBtrfsSupervisor
import borg.trikeshed.graal.subvm.PythonWheels
import borg.trikeshed.pointcut.VmFacet
import borg.trikeshed.parse.yaml.YamlParser
import borg.trikeshed.vm.Teleported
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.io.OutputStream
import kotlinx.coroutines.CoroutineScope
import org.graalvm.polyglot.PolyglotException
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText

data class NativeModuleBan(
    val module: String,
    val reason: String,
    val replacement: String,
)

object HermesNativeModuleBanlist {
    fun load(): Map<String, NativeModuleBan> {
        val stream = HermesNativeModuleBanlist::class.java.classLoader
            .getResourceAsStream("hermes-native-module-banlist.txt")
            ?: error("missing hermes-native-module-banlist.txt")
        return stream.bufferedReader().useLines { lines ->
            lines.filter { it.isNotBlank() && !it.trimStart().startsWith('#') }
                .map { line ->
                    val cells = line.split('|', limit = 3)
                    require(cells.size == 3) { "invalid native module ban: $line" }
                    NativeModuleBan(cells[0].trim(), cells[1].trim(), cells[2].trim())
                }
                .associateBy { it.module }
        }
    }
}

enum class HermesModuleStatus { READY, BLOCKED_NATIVE, BLOCKED_TRANSITIVE }

data class HermesPythonModule(
    val name: String,
    val relativePath: String,
    val source: String,
    val packageModule: Boolean,
    val imports: Set<String>,
    val deferredImports: Set<String>,
    val localImports: Set<String>,
    val externalImports: Set<String>,
    val nativeBlocks: Set<String>,
    val transitiveBlocks: Set<String> = emptySet(),
    val sleeved: Boolean = false,
) {
    val status: HermesModuleStatus get() = when {
        nativeBlocks.isNotEmpty() -> HermesModuleStatus.BLOCKED_NATIVE
        transitiveBlocks.isNotEmpty() -> HermesModuleStatus.BLOCKED_TRANSITIVE
        else -> HermesModuleStatus.READY
    }

    val blockedBy: Set<String> get() = nativeBlocks + transitiveBlocks
    val origin: String get() = if (sleeved) "sleeve" else if (relativePath.startsWith("wheel:")) "wheel" else "upstream"
    val sourceSpineCid: String get() = LineCas.spineCid(LineCas.spine(source)).hex
}

data class HermesSignificantGap(
    val root: String,
    val impacted: Int,
    val direct: Int,
    val deferred: Int,
    val reason: String,
    val replacement: String,
    val modules: Series<String>,
)

data class HermesPortInventory(
    val root: Path?,
    val modules: Map<String, HermesPythonModule>,
    val banlist: Map<String, NativeModuleBan>,
    val upstreamSpineCid: String,
    val sleeveSpineCid: String,
    val sleeveRoot: Path? = null,
    val dependencies: PythonWheels? = null,
) {
    val ready: Int get() = modules.values.count { it.status == HermesModuleStatus.READY }
    val blockedNative: Int get() = modules.values.count { it.status == HermesModuleStatus.BLOCKED_NATIVE }
    val blockedTransitive: Int get() = modules.values.count { it.status == HermesModuleStatus.BLOCKED_TRANSITIVE }

    val ontology: HermesOntologySpine by lazy {
        val facts = ArrayList<HermesOntologyFact>(modules.size * 2)
        val orderedModules = modules.values.toTypedArray().apply { sortBy { it.name } }
        for (module in orderedModules) {
            // Avoids O(N) intermediate wrapper allocations (Sequence, Iterator, lambda instances) per module,
            // significantly reducing garbage collection pressure during frequent ontology regeneration.
            val roots = sortedSetOf<String>()
            for (blocked in module.blockedBy) {
                if (blocked in banlist) roots.add(blocked)
            }
            if (roots.isEmpty()) {
                facts += HermesOntologyFact(HermesOntologyKind.READY, module.origin, module.name)
            } else {
                for (blocker in roots) {
                    facts += HermesOntologyFact(HermesOntologyKind.BLOCKED, blocker, module.name)
                }
            }
            val deferred = sortedSetOf<String>()
            for (imp in module.deferredImports) {
                val prefix = imp.substringBefore('.')
                if (prefix in banlist) deferred.add(prefix)
            }
            for (blocker in deferred) {
                facts += HermesOntologyFact(HermesOntologyKind.DEFERRED, blocker, module.name)
            }
        }
        hermesOntologySpine(facts.toSeries())
    }

    fun significantGaps(limit: Int = 15): Series<HermesSignificantGap> {
        val deferredByRoot = ontology.zoom(LineAperture.L1).view.associate { node ->
            node.a.view.joinToString("/") to node.b
        }
        val gaps = ArrayList<HermesSignificantGap>()
        for (node in ontology.zoom(LineAperture.L1).view) {
            val prefix = node.a
            if (prefix.size != 2 || prefix[0] != HermesOntologyKind.BLOCKED.token) continue
            val blocker = prefix[1]
            val ban = banlist[blocker] ?: continue
            val impactedModules = ArrayList<String>()
            for (module in modules.values) if (blocker in module.blockedBy) impactedModules += module.name
            impactedModules.sort()
            gaps += HermesSignificantGap(
                root = blocker,
                impacted = node.b,
                direct = modules.values.count { blocker in it.nativeBlocks },
                deferred = deferredByRoot["${HermesOntologyKind.DEFERRED.token}/$blocker"] ?: 0,
                reason = ban.reason,
                replacement = ban.replacement,
                modules = impactedModules.size j { i: Int -> impactedModules[i] },
            )
        }
        gaps.sortWith(compareByDescending<HermesSignificantGap> { it.impacted }.thenByDescending { it.direct }.thenBy { it.root })
        val count = minOf(limit, gaps.size)
        return count j { i: Int -> gaps[i] }
    }

    fun toMap(): Map<String, Any?> = mapOf(
        "root" to root?.toString(),
        "sleeveRoot" to sleeveRoot?.toString(),
        "upstreamSpineCid" to upstreamSpineCid,
        "sleeveSpineCid" to sleeveSpineCid,
        "dependencies" to dependencies?.manifest?.entries?.map {
            mapOf("file" to it.file, "bytes" to it.size, "sha256" to it.sha256)
        }.orEmpty(),
        "summary" to mapOf(
            "modules" to modules.size,
            "ready" to ready,
            "blockedNative" to blockedNative,
            "blockedTransitive" to blockedTransitive,
            "banlistEntries" to banlist.size,
        ),
        "banlist" to banlist.values.sortedBy { it.module }.map {
            mapOf("module" to it.module, "reason" to it.reason, "replacement" to it.replacement)
        },
        "modules" to modules.values.sortedBy { it.name }.map { module ->
            mapOf(
                "name" to module.name,
                "path" to module.relativePath,
                "status" to module.status.name.lowercase(),
                "origin" to module.origin,
                "sourceSpineCid" to module.sourceSpineCid,
                "imports" to module.imports.sorted(),
                "deferredImports" to module.deferredImports.sorted(),
                "externalImports" to module.externalImports.sorted(),
                "blockedBy" to module.blockedBy.sorted(),
            )
        },
        "ontology" to mapOf(
            "spineCid" to ontology.cid.hex,
            "lines" to ontology.lines.view.toList(),
            "zoom" to LineAperture.entries.associate { aperture ->
                aperture.name to ontology.zoom(aperture).view.map { node ->
                    mapOf("prefix" to node.a.view.toList(), "count" to node.b)
                }
            },
        ),
        "significantGaps" to significantGaps().view.map { gap -> gap.toMap() },
    )
}

internal fun HermesSignificantGap.toMap(): Map<String, Any?> = mapOf(
    "root" to root,
    "impacted" to impacted,
    "direct" to direct,
    "deferred" to deferred,
    "reason" to reason,
    "replacement" to replacement,
    "modules" to modules.view.toList(),
)

/**
 * Projects Hermes' Python source graph onto the blackboard before any guest code runs. Native
 * dependency edges are fail-closed and propagated through local imports, leaving a deterministic
 * queue of modules that need a pure-Python or CCEK-host replacement.
 */
class HermesPythonPort(
    private val blackboard: ConfixBlackboard = ConfixBlackboard.empty(),
    private val banlist: Map<String, NativeModuleBan> = HermesNativeModuleBanlist.load(),
    private val output: OutputStream? = null,
    private val error: OutputStream? = output,
    /** THE PEN (HermesPenDelegates): installed beside the import waist when leased. */
    private val pen: HermesPen? = null,
    private val socketScope: CoroutineScope? = null,
    /** Host-owned services installed before source imports, under the existing guest bounds. */
    private val install: (GraalBtrfsSupervisor) -> Unit = {},
    /** The same import-site evidence projected to the blackboard, delivered while the guest is running. */
    private val importPointcut: (Map<String, Any?>) -> Unit = {},
    private val world: BtrfsWorldStore = BtrfsWorldStore.ofMemory(),
) : AutoCloseable {
    private var isolate: GraalBtrfsSupervisor? = null
    private var isolateSockets: HermesSocketProvider? = null
    private var isolateSqlite: HermesSqlite? = null

    fun inventory(root: Path, sleeveRoot: Path? = null): HermesPortInventory {
        require(Files.isDirectory(root)) { "Hermes source root is not a directory: $root" }
        val upstream = readSources(root)
        val sleeve = sleeveRoot?.takeIf(Files::isDirectory)?.let { readSources(it, "sleeve:") }.orEmpty()
        return inventorySources(upstream, root, sleeve, sleeveRoot, PythonWheels.installed())
    }

    private fun readSources(root: Path, displayPrefix: String = ""): Map<String, Pair<String, String>> {
        val sources = linkedMapOf<String, Pair<String, String>>()
        val paths = mutableListOf<Path>()
        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                val name = dir.fileName.toString()
                return if (dir != root && (name.startsWith('.') || name in EXCLUDED_DIRS)) {
                    FileVisitResult.SKIP_SUBTREE
                } else FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (include(root, file)) paths.add(file)
                return FileVisitResult.CONTINUE
            }
        })
        for (path in paths.sorted()) {
            val relative = root.relativize(path).toString().replace('\\', '/')
            moduleName(relative)?.let { name -> sources[name] = "$displayPrefix$relative" to path.readText() }
        }
        return sources
    }

    fun inventorySources(
        sources: Map<String, Pair<String, String>>,
        root: Path? = null,
        sleeveSources: Map<String, Pair<String, String>> = emptyMap(),
        sleeveRoot: Path? = null,
        dependencies: PythonWheels? = null,
    ): HermesPortInventory {
        val dependencySources = dependencies?.sources.orEmpty()
        require(dependencySources.keys.none { it in sources || it in sleeveSources }) { "Python dependency shadows an upstream or sleeve module" }
        val supplied = if (pen == null) sleeveSources else LinkedHashMap(sleeveSources).apply {
            if (HermesContextVars.MODULE !in sources && HermesContextVars.MODULE !in this &&
                HermesContextVars.MODULE !in dependencySources) {
                put(HermesContextVars.MODULE, "sleeve:contextvars.py" to HermesContextVars.source)
            }
            if (GUEST_PRELUDE !in sources && GUEST_PRELUDE !in this) {
                require(GUEST_PRELUDE !in dependencySources) { "Hermes guest prelude is host-owned" }
                put(GUEST_PRELUDE, "sleeve:$GUEST_PRELUDE.py" to HermesGuestPrelude.source(socketScope != null))
            }
            require(HermesModelProvider.MODULE !in sources && HermesModelProvider.MODULE !in this && HermesModelProvider.MODULE !in dependencySources) {
                "Hermes model transport module is host-owned: ${HermesModelProvider.MODULE}"
            }
            put(HermesModelProvider.MODULE, "sleeve:${HermesModelProvider.MODULE}.py" to HermesModelProvider.source)
            if (socketScope != null) {
                require(HermesSocketProvider.MODULE !in sources && HermesSocketProvider.MODULE !in this && HermesSocketProvider.MODULE !in dependencySources) {
                    "Hermes socket transport module is host-owned: ${HermesSocketProvider.MODULE}"
                }
                put(HermesSocketProvider.MODULE, "sleeve:socket.py" to HermesSocketProvider.source)
                require(HermesTlsProvider.MODULE !in sources && HermesTlsProvider.MODULE !in this && HermesTlsProvider.MODULE !in dependencySources) {
                    "Hermes TLS transport module is host-owned: ${HermesTlsProvider.MODULE}"
                }
                put(HermesTlsProvider.MODULE, "sleeve:ssl.py" to HermesTlsProvider.source)
            }
        }
        val combined = LinkedHashMap(dependencySources).apply { putAll(sources); putAll(supplied) }
        val names = combined.keys
        val sleevedNames = supplied.keys
        var modules = combined.mapValues { (name, value) ->
            val (relative, source) = value
            val scan = importsOf(name, source, relative.endsWith("/__init__.py"), names)
            val imports = scan.required
            val local = imports.filterTo(sortedSetOf()) { resolveLocal(it, names) != null }
            val external = imports.filterTo(sortedSetOf()) { resolveLocal(it, names) == null }
            val blocked = imports.filterTo(sortedSetOf()) { imported ->
                val nativeRoot = imported.substringBefore('.')
                val localReplacement = resolveLocal(imported, names)
                nativeRoot in banlist && localReplacement !in sleevedNames
            }.mapTo(sortedSetOf()) { it.substringBefore('.') }
            HermesPythonModule(
                name, relative, source, relative.endsWith("/__init__.py"), imports, scan.deferred,
                local, external, blocked, sleeved = name in sleevedNames,
            )
        }

        var changed: Boolean
        do {
            changed = false
            val next = modules.mapValues { (_, module) ->
                val inherited = module.localImports.mapNotNull { resolveLocal(it, names) }
                    .flatMapTo(sortedSetOf()) { dependency ->
                        val blocked = modules[dependency]?.blockedBy.orEmpty()
                        if (blocked.isEmpty()) emptySet() else setOf(dependency) + blocked
                    }
                if (inherited != module.transitiveBlocks) {
                    changed = true
                    module.copy(transitiveBlocks = inherited)
                } else module
            }
            modules = next
        } while (changed)

        return HermesPortInventory(
            root = root,
            modules = modules,
            banlist = banlist,
            upstreamSpineCid = corpusSpineCid(sources),
            sleeveSpineCid = corpusSpineCid(supplied),
            sleeveRoot = sleeveRoot,
            dependencies = dependencies,
        ).also(::projectInventory)
    }

    /** Replace the owned VM only after a READY module imports successfully; a failed candidate is closed. */
    fun importInVm(inventory: HermesPortInventory, entry: String): Teleported {
        val module = inventory.modules[entry] ?: error("Hermes module not found: $entry")
        require(module.status == HermesModuleStatus.READY) {
            "Hermes module $entry is ${module.status.name.lowercase()}: ${module.blockedBy.sorted().joinToString()}"
        }
        val importRevision = blackboard.snapshot().revision
        val baseline = "boot-${inventory.ontology.cid.hex.take(16)}"
        val guest = GraalBtrfsSupervisor(
            id = "hermes-python",
            facet = VmFacet.GRAAL_PYTHON,
            budget = Budget(statements = 0, wallMillis = IMPORT_WALL_MILLIS, calls = 0),
            output = output,
            error = error,
            world = world,
        )
        val sqlite = HermesSqlite(
            readImage = guest.vfs::fetch,
            writeImage = guest::put,
            callFunction = { function, arguments ->
                val result = guest.call("__trikeshed_sqlite_function", Teleported.Str(function),
                    Teleported.Str(jsonOf(arguments.map(HermesSqlite::encode))))
                val encoded = (result as? Teleported.Str)?.v ?: error("SQLite callback must return JSON")
                HermesSqlite.decode(reify(encoded))
            },
        )
        var sockets: HermesSocketProvider? = null
        val result = try {
            if (pen != null && socketScope != null) sockets = HermesSocketProvider(socketScope)
            guest.delegate("sqlite3") { args ->
                val request = (args.singleOrNull() as? Teleported.Str)?.v
                    ?: throw IllegalArgumentException("sqlite3 requires a JSON request")
                Teleported.Str(jsonOf(sqlite.call(reifyMap(request))))
            }
            guest.eval("""
                def __trikeshed_sqlite_function(function, arguments):
                    from sqlite3 import __trikeshed_sqlite_callback
                    return __trikeshed_sqlite_callback(function, arguments)
                True
            """.trimIndent(), "hermes-sqlite-callback.py")
            val imported = importInVm(guest, inventory, entry, baseline, sockets)
            isolate?.close()
            isolateSockets?.close()
            isolateSqlite?.close()
            imported
        } catch (failure: Throwable) {
            try { importFailure(entry, importRevision, failure) } catch (diagnostic: Throwable) { failure.addSuppressed(diagnostic) }
            try { guest.close() } catch (closing: Throwable) { failure.addSuppressed(closing) }
            try { sockets?.close() } catch (closing: Throwable) { failure.addSuppressed(closing) }
            try { sqlite.close() } catch (closing: Throwable) { failure.addSuppressed(closing) }
            throw failure
        }
        isolate = guest
        isolateSockets = sockets
        isolateSqlite = sqlite
        blackboard.put(
            "hermes/python/vfs",
            mapOf("backend" to "userspace-btrfs", "subvolume" to guest.vfs.liveSubvolumeForTools(), "baseline" to result.b, "generation" to guest.vfs.generation()),
            "graal-python",
        )
        return result.a
    }

    private fun importInVm(guest: GraalBtrfsSupervisor, inventory: HermesPortInventory, entry: String, baseline: String,
                           sockets: HermesSocketProvider?): Join<Teleported, String> {
        val files = LinkedHashMap<String, ByteArray>()
        for ((path, bytes) in inventory.dependencies?.resources.orEmpty()) files["/workspace/$path"] = bytes
        for (candidate in inventory.modules.values) {
            val modulePath = candidate.name.replace('.', '/') + if (candidate.packageModule) "/__init__.py" else ".py"
            files["/workspace/$modulePath"] = candidate.source.encodeToByteArray()
        }
        guest.put(files.entries.toTypedArray().toSeries() α { it.key j it.value })
        val volume = guest.vfs.btrfsForTools()
        val manifest = requireNotNull(volume.manifestId(guest.vfs.liveSubvolumeForTools()))
        val mountedBaseline = "$baseline-${manifest.hex.take(16)}"
        check(if (volume.hasSubvolume(mountedBaseline)) volume.manifestId(mountedBaseline) == manifest
              else guest.snapshot(mountedBaseline)) { "unable to snapshot Hermes Graal VFS baseline $mountedBaseline" }
        // A directory with no __init__.py is a PEP 420 namespace package on a real filesystem,
        // and Hermes leans on that: `plugins/platforms`, `plugins/browser`, `scripts` and friends
        // carry no __init__.py, so they never enter the inventory and every module beneath them
        // was unreachable — the single largest class of import misses in the guest.
        val namespaces = sortedSetOf<String>().apply {
            for (name in inventory.modules.keys) {
                var parent = name.substringBeforeLast('.', "")
                while (parent.isNotEmpty() && parent !in inventory.modules) {
                    add(parent); parent = parent.substringBeforeLast('.', "")
                }
            }
        }
        guest.delegate("verdict") { args ->
            val name = (args.firstOrNull() as? Teleported.Str)?.v.orEmpty()
            val root = name.substringBefore('.')
            when {
                inventory.modules[name]?.sleeved == true -> Teleported.Str("allow")
                root in banlist -> Teleported.Str("ban:${banlist.getValue(root).reason}")
                name in inventory.modules -> Teleported.Str("allow")
                name in namespaces -> Teleported.Str("namespace")
                else -> Teleported.Str("miss")
            }
        }
        guest.delegate("source") { args ->
            val name = (args.firstOrNull() as? Teleported.Str)?.v.orEmpty()
            inventory.modules[name]?.let { Teleported.Str(it.source) } ?: Teleported.Null
        }
        guest.delegate("is_package") { args ->
            val name = (args.firstOrNull() as? Teleported.Str)?.v.orEmpty()
            Teleported.Bool(inventory.modules[name]?.packageModule == true)
        }
        guest.delegate("path") { args ->
            val name = (args.firstOrNull() as? Teleported.Str)?.v.orEmpty()
            val candidate = inventory.modules[name]
            val suffix = if (candidate?.packageModule == true) "/__init__.py" else ".py"
            Teleported.Str("/workspace/${name.replace('.', '/')}$suffix")
        }
        guest.delegate("distributions") {
            Teleported.Arr(inventory.dependencies?.resources.orEmpty().keys
                .filter { it.endsWith(".dist-info/METADATA") }
                .sorted().map { Teleported.Str("/workspace/" + it.removeSuffix("/METADATA")) })
        }
        guest.delegate("yaml_load") { args ->
            val text = (args.firstOrNull() as? Teleported.Str)?.v
                ?: throw IllegalArgumentException("yaml_load requires text")
            Teleported.Str(jsonOf(YamlParser.reify(text)))
        }
        guest.delegate("host_facts") { Teleported.Str(jsonOf(HermesHostFacts.snapshot())) }
        guest.delegate("process_alive") { args ->
            val pid = (args.singleOrNull() as? Teleported.Num)?.v
                ?: throw IllegalArgumentException("process_alive requires a PID")
            Teleported.Bool(ProcessHandle.of(pid).map { it.isAlive }.orElse(false))
        }
        guest.delegate("land") { args ->
            val name = (args.firstOrNull() as? Teleported.Str)?.v.orEmpty()
            val status = (args.getOrNull(1) as? Teleported.Str)?.v ?: "loaded"
            require(status in setOf("loading", "loaded", "failed", "refused")) { "invalid import status" }
            val failure = (args.getOrNull(2) as? Teleported.Str)?.v
            val traceback = (args.getOrNull(3) as? Teleported.Str)?.v
            val exceptionId = (args.getOrNull(4) as? Teleported.Str)?.v
            land(name, status, failure, traceback, exceptionId = exceptionId)
            Teleported.Null
        }
        install(guest)
        pen?.install(guest, sockets)
        val entryPath = "/workspace/" + entry.replace('.', '/') +
            if (inventory.modules.getValue(entry).packageModule) "/__init__.py" else ".py"
        // Embedded GraalPy starts with no argv[0]; the donor expects its actual
        // script identity even when this host imports the entry instead of executing it.
        guest.eval("import sys\nif not sys.argv:\n    sys.argv.append(${pythonString(entryPath)})\nTrue", "hermes-entry-arguments.py")
        guest.eval(IMPORTER_BOOTSTRAP, "hermes-blackboard-importer.py")
        // The prelude twins the capabilities the polyglot bounds withhold, and it has to run
        // before the guest binds them: stdlib `threading` copies `_thread.start_joinable_thread`
        // into a module global at import, so a twin installed after that import is never seen.
        if (GUEST_PRELUDE in inventory.modules) {
            guest.eval("import importlib\nimportlib.import_module(${pythonString(GUEST_PRELUDE)})\nTrue", "hermes-guest-prelude.py")
        }
        return guest.eval("import importlib\nimportlib.import_module(${pythonString(entry)})\nTrue", "hermes-entry.py") j mountedBaseline
    }

    private fun land(name: String, status: String, failure: String? = null, traceback: String? = null, origin: String = "guest", exceptionId: String? = null) {
        val pointcut = mapOf("module" to name, "status" to status, "error" to failure, "traceback" to traceback, "origin" to origin, "exceptionId" to exceptionId)
        blackboard.put("hermes/python/pointcut/import/$name", pointcut, "graal-python")
        importPointcut(pointcut)
    }

    /** A watchdog may leave Python before its except block can land the active import. */
    private fun importFailure(entry: String, afterRevision: Long, failure: Throwable) {
        val snapshot = blackboard.snapshot()
        val prefix = "hermes/python/pointcut/import/"
        val current = snapshot.values.filterKeys { key ->
            key.startsWith(prefix) && (snapshot.provenance[key]?.revision ?: 0) > afterRevision
        }
        val traceback = generateSequence(failure) { it.cause }.filterIsInstance<PolyglotException>().firstOrNull()?.let { guest ->
            runCatching {
                guest.polyglotStackTrace.filter { it.isGuestFrame }.joinToString("\n").takeIf { it.isNotBlank() }
            }.getOrNull()
        }
        val error = failure.message ?: failure::class.simpleName
        val loading = current.entries.filter { (_, value) -> (value as? Map<*, *>)?.get("status") == "loading" }
            .sortedByDescending { (key, _) -> snapshot.provenance[key]?.revision ?: 0 }
        for ((key, _) in loading) land(key.removePrefix(prefix), "failed", error, traceback, "host")
        val entryPointcut = current["$prefix$entry"] as? Map<*, *>
        if (entryPointcut?.get("status") !in setOf("failed", "refused") && loading.none { it.key == "$prefix$entry" }) {
            land(entry, "failed", error, traceback, "host")
        }
    }

    fun blackboard(): ConfixBlackboard = blackboard

    val vmStarted: Boolean get() = isolate?.isAlive == true

    /** Evaluate on the already-imported Hermes isolate; used by console/dashboard front ends. */
    fun evalInVm(source: String, name: String = "hermes-console.py"): Teleported =
        isolate?.eval(source, name) ?: error("Hermes VM has not been imported")

    override fun close() {
        try { isolate?.close() } finally {
            isolate = null
            try { isolateSockets?.close() } finally {
                isolateSockets = null
                try { isolateSqlite?.close() } finally { isolateSqlite = null }
            }
        }
    }

    private fun projectInventory(inventory: HermesPortInventory) {
        inventory.modules.values.forEach { module ->
            blackboard.put(
                "hermes/python/module/${module.name}",
                mapOf(
                    "name" to module.name,
                    "path" to module.relativePath,
                    "origin" to module.origin,
                    "status" to module.status.name.lowercase(),
                    "imports" to module.imports.sorted(),
                    "deferredImports" to module.deferredImports.sorted(),
                    "externalImports" to module.externalImports.sorted(),
                    "blockedBy" to module.blockedBy.sorted(),
                ),
                "hermes-port",
            )
        }
        blackboard.put(
            "hermes/python/triage",
            mapOf(
                "modules" to inventory.modules.size,
                "ready" to inventory.ready,
                "blockedNative" to inventory.blockedNative,
                "blockedTransitive" to inventory.blockedTransitive,
                "ontologySpineCid" to inventory.ontology.cid.hex,
            ),
            "hermes-port",
        )
        for (gap in inventory.significantGaps().view) {
            blackboard.put("hermes/python/gap/${gap.root}", gap.toMap(), "hermes-port")
        }
    }

    private fun corpusSpineCid(sources: Map<String, Pair<String, String>>): String {
        if (sources.isEmpty()) return LineCas.spineCid(LineCas.spine("")).hex
        val text = buildString {
            sources.entries.sortedBy { it.key }.forEachIndexed { index, (name, value) ->
                if (index > 0) append('\n')
                append("module/").append(name).append('\n')
                append("path/").append(value.first).append('\n')
                append(value.second)
            }
        }
        return LineCas.spineCid(LineCas.spine(text)).hex
    }

    private data class ImportScan(val required: Set<String>, val deferred: Set<String>)

    /** Column-zero imports execute when the module loads. Indented imports are projected for later triage, not propagated as load blockers. */
    private fun importsOf(module: String, source: String, packageModule: Boolean, names: Set<String>): ImportScan {
        val required = sortedSetOf<String>()
        val deferred = sortedSetOf<String>()
        var tripleQuote: String? = null
        source.lineSequence().forEach { line ->
            val activeQuote = tripleQuote
            if (activeQuote != null) {
                if (line.contains(activeQuote)) tripleQuote = null
                return@forEach
            }
            val doubleQuote = line.indexOf("\"\"\"")
            val singleQuote = line.indexOf("'''")
            val triple = when {
                doubleQuote < 0 && singleQuote < 0 -> null
                doubleQuote >= 0 && (singleQuote < 0 || doubleQuote < singleQuote) -> "\"\"\"" to doubleQuote
                else -> "'''" to singleQuote
            }
            if (triple != null) {
                val closes = line.indexOf(triple.first, triple.second + 3) >= 0
                if (!closes) tripleQuote = triple.first
                if (line.substring(0, triple.second).isBlank()) return@forEach
            }

            val trimmed = line.trimStart()
            val target = if (trimmed.length == line.length) required else deferred
            FROM_IMPORT.matchEntire(trimmed)?.let { match ->
                val raw = match.groupValues[1]
                val absolute = if (raw.startsWith('.')) resolveRelative(module, packageModule, raw) else raw
                if (absolute.isNotBlank()) target += absolute
                return@forEach
            }
            IMPORT.matchEntire(trimmed)?.let { match ->
                for (candidate in match.groupValues[1].split(',')) {
                    val imported = candidate.trim().substringBefore(' ')
                    if (imported.matches(MODULE_NAME)) target += imported
                }
            }
        }
        fun resolved(imports: Set<String>): Set<String> = imports.mapTo(sortedSetOf()) { resolveLocal(it, names) ?: it }
        return ImportScan(resolved(required), resolved(deferred) - resolved(required))
    }

    private fun resolveRelative(module: String, packageModule: Boolean, raw: String): String {
        val dots = raw.takeWhile { it == '.' }.length
        val suffix = raw.drop(dots)
        val base = if (packageModule) module.split('.') else module.substringBeforeLast('.', "").split('.').filter { it.isNotEmpty() }
        val keep = (base.size - (dots - 1)).coerceAtLeast(0)
        return (base.take(keep) + suffix.split('.').filter { it.isNotEmpty() }).joinToString(".")
    }

    private fun resolveLocal(imported: String, names: Set<String>): String? {
        var candidate = imported
        while (candidate.isNotEmpty()) {
            if (candidate in names) return candidate
            candidate = candidate.substringBeforeLast('.', "")
        }
        return null
    }

    private fun include(root: Path, path: Path): Boolean {
        if (!path.isRegularFile() || path.extension != "py") return false
        if (root.relativize(path).any { part ->
                val value = part.toString()
                value.startsWith('.') || value in EXCLUDED_DIRS
            }) return false
        return true
    }

    private fun moduleName(relative: String): String? {
        val cells = relative.removeSuffix(".py").split('/').toMutableList()
        if (cells.lastOrNull() == "__init__") cells.removeLast()
        return cells.takeIf { it.isNotEmpty() }?.joinToString(".")
    }

    companion object {
        const val IMPORT_WALL_MILLIS = 60_000L
        /** Sleeve module holding the withheld/ported capability twins; imported at the waist when present. */
        const val GUEST_PRELUDE = "trikeshed_guest_prelude"
        private val EXCLUDED_DIRS = setOf("__pycache__", "build", "dist", "docs", "node_modules", "optional-skills", "skills", "tests", "venv")
        private val MODULE_NAME = Regex("[A-Za-z_][A-Za-z0-9_.]*")
        private val FROM_IMPORT = Regex("from\\s+([.A-Za-z_][A-Za-z0-9_.]*)\\s+import\\s+.+")
        private val IMPORT = Regex("import\\s+([^#]+)")

        private fun pythonString(value: String): String = buildString {
            append('\'')
            value.forEach { ch ->
                when (ch) {
                    '\\' -> append("\\\\")
                    '\'' -> append("\\'")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    else -> append(ch)
                }
            }
            append('\'')
        }

        private val IMPORTER_BOOTSTRAP = """
            import sys, importlib.abc, importlib.machinery, importlib.util
            import traceback as _hermes_traceback

            # This is the guest Btrfs directory, not a host site-packages path. Standard
            # distribution discovery reads the verified wheels' .dist-info here.
            if '/workspace' not in sys.path:
                sys.path.append('/workspace')

            class _HermesBlackboardImporter(importlib.abc.MetaPathFinder, importlib.abc.Loader):
                def namespace_spec(self, fullname, path=None):
                    spec = importlib.machinery.ModuleSpec(fullname, None, is_package=True)
                    spec.submodule_search_locations = ['/workspace/' + fullname.replace('.', '/')]
                    return spec

                def find_spec(self, fullname, path=None, target=None):
                    verdict = host.call('verdict', fullname)
                    if verdict.startswith('ban:'):
                        failure = ImportError('native module banned in TrikeShed guest: ' + fullname + ': ' + verdict[4:])
                        host.call('land', fullname, 'refused', str(failure), None, str(id(failure)))
                        raise failure
                    if verdict == 'namespace':
                        # A directory with no __init__.py: PEP 420 shape, no source to execute.
                        from importlib._bootstrap_external import _NamespacePath
                        spec = self.namespace_spec(fullname, path)
                        spec.submodule_search_locations = _NamespacePath(fullname, spec.submodule_search_locations, self.namespace_spec)
                        return spec
                    if verdict != 'allow':
                        return None
                    is_package = bool(host.call('is_package', fullname))
                    return importlib.util.spec_from_loader(fullname, self, is_package=is_package)

                def create_module(self, spec):
                    return None

                def get_filename(self, fullname):
                    return host.call('path', fullname)

                def get_resource_reader(self, fullname):
                    if not host.call('is_package', fullname):
                        return None
                    from importlib.resources.readers import FileReader
                    return FileReader(importlib.machinery.SourceFileLoader(fullname, self.get_filename(fullname)))

                def find_distributions(self, context=None):
                    # Top-level Btrfs listings mask names. Discover only the verified
                    # distribution paths, without disabling containment or inventing metadata.
                    from importlib.metadata import DistributionFinder, PathDistribution
                    from pathlib import Path
                    import re
                    if context is None:
                        context = DistributionFinder.Context()
                    if '/workspace' not in context.path:
                        return
                    def normalized(name):
                        return re.sub(r'[-_.]+', '-', name).lower()
                    for path in host.call('distributions'):
                        distribution = PathDistribution(Path(path))
                        if context.name is None or normalized(distribution.metadata['Name']) == normalized(context.name):
                            yield distribution

                def exec_module(self, module):
                    name = module.__name__
                    source = host.call('source', name)
                    module.__file__ = host.call('path', name)
                    module.__dict__['host'] = host
                    host.call('land', name, 'loading')
                    try:
                        exec(compile(source, module.__file__, 'exec'), module.__dict__)
                    except BaseException as failure:
                        detail = None
                        try:
                            detail = ''.join(_hermes_traceback.format_exception(type(failure), failure, failure.__traceback__))
                        except BaseException:
                            pass
                        host.call('land', name, 'failed', str(failure), detail, str(id(failure)))
                        raise
                    host.call('land', name, 'loaded')

            sys.meta_path.insert(0, _HermesBlackboardImporter())
            True
        """.trimIndent()
    }
}
