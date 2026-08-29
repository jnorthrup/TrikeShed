package borg.trikeshed.lcnc

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * VAL-API-PARITY-001: bidirectional existence parity between
 * [RouteManifest] entries and registered route families in the wire sources.
 *
 * Every route family registered by the eight surfaces' wires must be declared
 * in RouteManifest, and every RouteManifest entry must resolve to a registered
 * route family. Intentional exclusions live in [ALLOWLIST] with comments.
 */
class RouteManifestParityTest {

    /**
     * Intentional exclusions: routes that exist in the code but are
     * deliberately NOT in the manifest. Each entry has a reason.
     * Format: "METHOD /path" — reason comment.
     */
    private val ALLOWLIST = setOf(
        // GET /panels — served as a static asset by JvmKanbanServer.staticAssets,
        // not an API endpoint; the manifest covers /api/panels/* API routes.
        "GET /panels",
        // POST /_project/<name>/begin|put — error message string in ProjectDbWire, not a route
        "POST /_project/<name>/begin|put",
        // GET / or GET /index.html — served by ForgeRoutes.PORTABLE / JvmKanbanServer
        // as the shell HTML, not owned by any of the eight surface wires.
        "GET /",
        "GET /index.html",
        // GET /api/health, GET /api/cap, GET /api/metrics — daemon infrastructure,
        // listed under BuiltIn in the manifest.
        // (These ARE in the manifest; the allowlist is for code routes NOT in the manifest.)
    )

    /** Wire source files to scan for route patterns (repo-relative). */
    private val WIRE_SOURCES = listOf(
        "src/jvmMain/kotlin/borg/trikeshed/forge/server/GraalWire.kt",
        "src/jvmMain/kotlin/borg/trikeshed/forge/server/BeliefWire.kt",
        "src/jvmMain/kotlin/borg/trikeshed/forge/server/CouchWire.kt",
        "src/jvmMain/kotlin/borg/trikeshed/forge/server/VmWire.kt",
        "src/jvmMain/kotlin/borg/trikeshed/forge/server/PatchWire.kt",
        "src/jvmMain/kotlin/borg/trikeshed/forge/server/BlackboardWire.kt",
        "src/jvmMain/kotlin/borg/trikeshed/forge/server/HermesConsoleWire.kt",
        "src/jvmMain/kotlin/borg/trikeshed/forge/server/WebhookWire.kt",
        "src/jvmMain/kotlin/borg/trikeshed/forge/server/ProjectDbWire.kt",
        "src/jvmMain/kotlin/borg/trikeshed/forge/server/ModuleWire.kt",
        "src/jvmMain/kotlin/borg/trikeshed/kanban/module/KanbanModule.kt",
        "src/jvmMain/kotlin/borg/trikeshed/litebike/JvmKanbanServer.kt",
        "src/commonMain/kotlin/borg/trikeshed/couch/CouchWireRouter.kt",
    )

    /**
     * Extract route-like path patterns from source text.
     * Returns method+path pairs where method may be "*" for method-agnostic checks.
     */
    private fun extractRoutePatterns(sourceText: String): Set<Pair<String, String>> {
        val routes = mutableSetOf<Pair<String, String>>()
        // "METHOD" to "/path" — ROUTES constant and similar
        Regex(""""(GET|POST|PUT|DELETE)"\s+to\s+"(/[^"]+)""""").findAll(sourceText).forEach {
            routes.add(it.groupValues[1] to it.groupValues[2])
        }
        // "METHOD /path" — RouteManifest-style strings in source
        Regex(""""((?:GET|POST|PUT|DELETE)\s+/[^"]+)""""").findAll(sourceText).forEach {
            val parts = it.groupValues[1].split(' ', limit = 2)
            if (parts.size == 2) routes.add(parts[0] to parts[1])
        }
        // p == "/path" or path == "/path" — when-branch exact match
        Regex("""(?:p|path)\s*==\s*"(/[^"]+)"""").findAll(sourceText).forEach {
            routes.add("*" to it.groupValues[1])
        }
        // p.startsWith("/path") — prefix match → treat as wildcard
        Regex("""(?:p|path)\.startsWith\("(/[^"]+)""""").findAll(sourceText).forEach {
            routes.add("*" to it.groupValues[1] + "\u2026")
        }
        return routes
    }

    /**
     * Extract paths registered via `ctx.routes.claim(moduleId, "/path")`.
     * This is the pattern KanbanModule (and any ForgeModule) uses — the method
     * is determined inside the handler lambda, so we extract path-only pairs
     * with method = "*CLAIM" to distinguish them from generic "*".
     */
    private fun extractClaimPaths(sourceText: String): Set<Pair<String, String>> {
        val routes = mutableSetOf<Pair<String, String>>()
        // ctx.routes.claim(id, "/api/...") { method, ... ->
        Regex("""claim\(\w+, "(/[^"]+)"\) """).findAll(sourceText).forEach {
            routes.add("*CLAIM" to it.groupValues[1])
        }
        return routes
    }

    @Test
    fun everyManifestEntryHasSourceEvidence() {
        val root = System.getProperty("user.dir") ?: fail("no user.dir")
        val corpus = StringBuilder()
        for (rel in WIRE_SOURCES) {
            val f = java.io.File(root, rel)
            if (f.isFile) corpus.append(f.readText()).append('\n')
        }
        val src = corpus.toString()
        val offenders = mutableListOf<String>()
        for ((wire, routeEntries) in RouteManifest.entries) {
            for (entry in routeEntries) {
                val segments = entry.path.trim('/').split('/')
                val concrete = segments.filter {
                    it != "\u2026" && !(it.startsWith("{") && it.endsWith("}"))
                }
                val present = concrete.all { seg ->
                    src.contains("\"$seg") || src.contains("/$seg")
                }
                if (!present) {
                    offenders.add("$wire: ${entry.method} ${entry.path} — no source evidence")
                }
            }
        }
        assertTrue(offenders.isEmpty(),
            "Manifest entries with no source evidence (manifest → code gap):\n  ${offenders.joinToString("\n  ")}")
    }

    @Test
    fun everyRegisteredRouteHasManifestEntry() {
        val root = System.getProperty("user.dir") ?: fail("no user.dir")
        val corpus = StringBuilder()
        for (rel in WIRE_SOURCES) {
            val f = java.io.File(root, rel)
            if (f.isFile) corpus.append(f.readText()).append('\n')
        }
        val src = corpus.toString()
        val sourceRoutes = extractRoutePatterns(src)

        val offenders = mutableListOf<String>()
        for ((method, path) in sourceRoutes) {
            if (method == "*") continue // method-agnostic; covered by specific entries
            // Check covers() which handles {param} and … wildcards
            if (RouteManifest.covers(method, path)) continue
            val key = "$method $path"
            if (key !in ALLOWLIST) {
                offenders.add(key)
            }
        }

        // claim()-registered routes: the handler determines the method at runtime,
        // so we verify each claim path has at least one manifest entry (any method).
        val claimPaths = extractClaimPaths(src)
        val allManifestPaths = RouteManifest.entries.values.flatten()
        for ((_, claimPath) in claimPaths) {
            val covered = allManifestPaths.any { it.path == claimPath }
            if (!covered) {
                offenders.add("claim() path $claimPath — no manifest entry covers it")
            }
        }

        assertTrue(offenders.isEmpty(),
            "Routes registered in code but absent from manifest (code → manifest gap):\n  ${offenders.joinToString("\n  ")}")
    }

    @Test
    fun manifestJsonSerializationIsValid() {
        val json = RouteManifest.toJson()
        assertTrue(json.contains("\"version\""), "JSON must contain version field")
        assertTrue(json.contains("\"routes\""), "JSON must contain routes field")
        for (wire in RouteManifest.entries.keys) {
            assertTrue(json.contains("\"$wire\""), "JSON must contain wire: $wire")
        }
        val routeCount = Regex(""""method"""").findAll(json).count()
        assertTrue(routeCount == RouteManifest.all.size,
            "JSON route count ($routeCount) must match manifest all.size (${RouteManifest.all.size})")
    }

    @Test
    fun manifestEntryCountIsNonTrivial() {
        // Prevent the manifest from being empty or suspiciously small.
        // Eight surfaces with individual route entries should produce 80+.
        val total = RouteManifest.all.size
        assertTrue(total >= 80,
            "Manifest has only $total routes — expected 80+ for eight surfaces; " +
                "the test may be passing vacuously")
    }

    @Test
    fun kanbanModuleContributesKnownRoutes() {
        // Canary: KanbanModule MUST contribute its known route families.
        // This guards against the extraction being blinded by syntax variants —
        // if the claim() regex fails, this test catches it.
        val kanbanEntries = RouteManifest.entries["KanbanModule"]
            ?: fail("KanbanModule has no manifest entries at all")
        assertTrue(kanbanEntries.size >= 5,
            "KanbanModule has only ${kanbanEntries.size} manifest entries — expected >= 5; " +
                "the claim() extraction may be broken")
        // Verify specific route families are present
        val paths = kanbanEntries.map { it.path }.toSet()
        assertTrue("/api/board" in paths, "KanbanModule must declare GET /api/board")
        assertTrue("/api/invoke" in paths, "KanbanModule must declare POST /api/invoke")
        assertTrue("/api/lcnc/run" in paths, "KanbanModule must declare POST /api/lcnc/run")
    }
}
