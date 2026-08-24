package borg.trikeshed.hermes

import borg.trikeshed.cas.LineCas
import borg.trikeshed.collections.LineAperture
import borg.trikeshed.graal.ConfixBlackboard
import borg.trikeshed.graal.subvm.Budget
import borg.trikeshed.lib.*
import borg.trikeshed.graal.subvm.GraalBtrfsSupervisor
import borg.trikeshed.pointcut.VmFacet
import borg.trikeshed.parse.yaml.YamlParser
import borg.trikeshed.parse.json.JsonSupport
import borg.trikeshed.vm.Teleported
import java.nio.file.Files
import java.nio.file.Path
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
) {
    val ready: Int get() = modules.values.count { it.status == HermesModuleStatus.READY }
    val blockedNative: Int get() = modules.values.count { it.status == HermesModuleStatus.BLOCKED_NATIVE }
    val blockedTransitive: Int get() = modules.values.count { it.status == HermesModuleStatus.BLOCKED_TRANSITIVE }

    val ontology: HermesOntologySpine by lazy {
        val facts = ArrayList<HermesOntologyFact>(modules.size * 2)
        val orderedModules = modules.values.toTypedArray().apply { sortBy { it.name } }
        for (module in orderedModules) {
            val roots = module.blockedBy.asSequence().filter { it in banlist }.toSortedSet()
            if (roots.isEmpty()) {
                facts += HermesOntologyFact(HermesOntologyKind.READY, if (module.sleeved) "sleeve" else "upstream", module.name)
            } else {
                roots.forEach { blocker -> facts += HermesOntologyFact(HermesOntologyKind.BLOCKED, blocker, module.name) }
            }
            val deferred = module.deferredImports.asSequence().map { it.substringBefore('.') }
                .filter { it in banlist }.toSortedSet()
            deferred.forEach { blocker ->
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
                "origin" to if (module.sleeved) "sleeve" else "upstream",
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
) : AutoCloseable {
    private var isolate: GraalBtrfsSupervisor? = null

    fun inventory(root: Path, sleeveRoot: Path? = null): HermesPortInventory {
        require(Files.isDirectory(root)) { "Hermes source root is not a directory: $root" }
        val upstream = readSources(root)
        val sleeve = sleeveRoot?.takeIf(Files::isDirectory)?.let { readSources(it, "sleeve:") }.orEmpty()
        return inventorySources(upstream, root, sleeve, sleeveRoot)
    }

    private fun readSources(root: Path, displayPrefix: String = ""): Map<String, Pair<String, String>> {
        val sources = linkedMapOf<String, Pair<String, String>>()
        Files.walk(root).use { paths ->
            paths.filter { path -> include(root, path) }.sorted().forEach { path ->
                val relative = root.relativize(path).toString().replace('\\', '/')
                moduleName(relative)?.let { name -> sources[name] = "$displayPrefix$relative" to path.readText() }
            }
        }
        return sources
    }

    fun inventorySources(
        sources: Map<String, Pair<String, String>>,
        root: Path? = null,
        sleeveSources: Map<String, Pair<String, String>> = emptyMap(),
        sleeveRoot: Path? = null,
    ): HermesPortInventory {
        val combined = LinkedHashMap(sources).apply { putAll(sleeveSources) }
        val names = combined.keys
        val sleevedNames = sleeveSources.keys
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
            sleeveSpineCid = corpusSpineCid(sleeveSources),
            sleeveRoot = sleeveRoot,
        ).also(::projectInventory)
    }

    /** Import one READY module in a GraalPy isolate with IO, processes, host classes and native access disabled. */
    fun importInVm(inventory: HermesPortInventory, entry: String): Teleported {
        val module = inventory.modules[entry] ?: error("Hermes module not found: $entry")
        require(module.status == HermesModuleStatus.READY) {
            "Hermes module $entry is ${module.status.name.lowercase()}: ${module.blockedBy.sorted().joinToString()}"
        }
        val guest = GraalBtrfsSupervisor(
            id = "hermes-python",
            facet = VmFacet.GRAAL_PYTHON,
            budget = Budget(statements = 0, wallMillis = 30_000, calls = 0),
        )
        isolate = guest
        for (candidate in inventory.modules.values) {
            val modulePath = candidate.name.replace('.', '/') + if (candidate.packageModule) "/__init__.py" else ".py"
            guest.put("/workspace/$modulePath", candidate.source.encodeToByteArray())
        }
        val baseline = "boot-${inventory.ontology.cid.hex.take(16)}"
        check(guest.snapshot(baseline)) { "unable to snapshot Hermes Graal VFS baseline $baseline" }
        blackboard.put(
            "hermes/python/vfs",
            mapOf("backend" to "userspace-btrfs", "subvolume" to "live", "baseline" to baseline, "generation" to guest.vfs.generation()),
            "graal-python",
        )
        guest.delegate("verdict") { args ->
            val name = (args.firstOrNull() as? Teleported.Str)?.v.orEmpty()
            val root = name.substringBefore('.')
            when {
                inventory.modules[name]?.sleeved == true -> Teleported.Str("allow")
                root in banlist -> Teleported.Str("ban:${banlist.getValue(root).reason}")
                name in inventory.modules -> Teleported.Str("allow")
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
        guest.delegate("yaml_load") { args ->
            val text = (args.firstOrNull() as? Teleported.Str)?.v
                ?: throw IllegalArgumentException("yaml_load requires text")
            Teleported.Str(JsonSupport.stringify(YamlParser.reify(text)))
        }
        guest.delegate("land") { args ->
            val name = (args.firstOrNull() as? Teleported.Str)?.v.orEmpty()
            blackboard.put("hermes/python/pointcut/import/$name", mapOf("module" to name, "status" to "loaded"), "graal-python")
            Teleported.Null
        }
        guest.eval(IMPORTER_BOOTSTRAP, "hermes-blackboard-importer.py")
        return try {
            guest.eval("import importlib\nimportlib.import_module(${pythonString(entry)})\nTrue", "hermes-entry.py")
        } catch (t: Throwable) {
            blackboard.put("hermes/python/pointcut/import/$entry", mapOf("module" to entry, "status" to "failed", "error" to (t.message ?: t::class.simpleName)), "graal-python")
            throw t
        }
    }

    fun blackboard(): ConfixBlackboard = blackboard

    override fun close() {
        isolate?.close()
        isolate = null
    }

    private fun projectInventory(inventory: HermesPortInventory) {
        inventory.modules.values.forEach { module ->
            blackboard.put(
                "hermes/python/module/${module.name}",
                mapOf(
                    "name" to module.name,
                    "path" to module.relativePath,
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
            import sys, importlib.abc, importlib.util

            class _HermesBlackboardImporter(importlib.abc.MetaPathFinder, importlib.abc.Loader):
                def find_spec(self, fullname, path=None, target=None):
                    verdict = host.call('verdict', fullname)
                    if verdict.startswith('ban:'):
                        raise ImportError('native module banned in TrikeShed guest: ' + fullname + ': ' + verdict[4:])
                    if verdict != 'allow':
                        return None
                    is_package = bool(host.call('is_package', fullname))
                    return importlib.util.spec_from_loader(fullname, self, is_package=is_package)

                def create_module(self, spec):
                    return None

                def exec_module(self, module):
                    name = module.__name__
                    source = host.call('source', name)
                    module.__file__ = host.call('path', name)
                    module.__dict__['host'] = host
                    host.call('land', name)
                    exec(compile(source, module.__file__, 'exec'), module.__dict__)

            sys.meta_path.insert(0, _HermesBlackboardImporter())
            True
        """.trimIndent()
    }
}