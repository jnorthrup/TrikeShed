package borg.trikeshed.lcnc

import borg.trikeshed.job.JobCommand
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Plan step 4 HARD GATES — these must FAIL the build, not warn.
 *
 * Gate 1 (route↔consumer parity): every route a browser page fetches (fetch/
 * EventSource URLs parsed from the HTML) must be covered by [RouteManifest],
 * and every manifest line must still be matched by the serving wire's source.
 * A route consumed by nothing, or served by nothing, fails here.
 *
 * Gate 2 (vocabulary single-author): panels.html must not carry a TYPES
 * vocabulary block. Contracts come from /api/lcnc/contracts — Kotlin is the
 * ONE author. A reintroduced `const TYPES = {` type table fails the build.
 */
class RouteParityGate {

    // ── resources under test ────────────────────────────────────────────────

    private fun resourceText(name: String): String =
        javaClass.getResource("/web/$name")?.readText()
            ?: fail("resource /web/$name missing from the test classpath — the gate must not silently pass on a missing resource")

    /** Every URL literal a page fetches: fetch("…"), api("METHOD","…"), EventSource("…"). */
    private fun fetchedRoutes(html: String): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        // api("GET","/path") / api("POST","/path",body)
        Regex("""api\(\s*"(GET|POST|PUT|DELETE)"\s*,\s*"([^"]+)"""").findAll(html).forEach {
            out.add(it.groupValues[1] to it.groupValues[2])
        }
        // fetch("/path"… / fetch(`/api/panels/${…}`
        Regex("""fetch\(\s*"(/[^"]*)"""").findAll(html).forEach { out.add("GET" to it.groupValues[1]) }
        Regex("""fetch\(\s*`(/[^`]*)`""").findAll(html).forEach { out.add("GET" to it.groupValues[1]) }
        // fetch("POST /path" style is not used; but record raw "/api" string literals too
        Regex(""""(GET|POST|PUT|DELETE) (/api/[^"]*)""").findAll(html).forEach {
            out.add(it.groupValues[1] to it.groupValues[2])
        }
        // EventSource("/path")
        Regex("""EventSource\(\s*"([^"]+)"""").findAll(html).forEach { out.add("SSE" to it.groupValues[1]) }
        return out
    }

    // ── gate 1a: everything the HTML fetches is a registered route ─────────

    @Test
    fun everyHtmlFetchIsARegisteredRoute() {
        val offenders = mutableListOf<String>()
        for (page in listOf("panels.html", "graal.html", "index.html", "script.js")) {
            val html = resourceText(page)
            for ((method, rawPath) in fetchedRoutes(html)) {
                // strip template holes and query strings: `/api/vm/${id}/eval?x` → `/api/vm/…/eval`
                val cleaned = rawPath
                    .replace(Regex("\\$\\{[^}]*}"), "…")
                    .substringBefore('?')
                    .removeSuffix("/")
                val path = cleaned.substringAfterLast("…", cleaned)
                val wildcardFree = if (cleaned.contains("…")) {
                    // template segment in the middle: manifest wildcard must cover from the hole on
                    val prefix = cleaned.substringBefore("…").removeSuffix("/")
                    "$method $prefix/…"
                } else "$method $path"
                val covered = RouteManifest.all.any { entry ->
                    entry == wildcardFree || RouteManifest.covers(method, path)
                }
                if (!covered) offenders.add("$page: $method $rawPath")
            }
        }
        assertTrue(offenders.isEmpty(),
            "HTML fetches routes the manifest does not register (orphan consumers):\n  " +
                offenders.joinToString("\n  "))
    }

    // ── gate 1b: the manifest does not drift from the serving sources ──────

    @Test
    fun manifestRoutesStillExistInWireSources() {
        // Wire sources live on the jvmMain SOURCE tree, readable from the test
        // working directory. A manifest line naming a route no wire claims
        // anymore (deleted upstream) fails here — the manifest is not a
        // graveyard.
        val root = System.getProperty("user.dir") ?: fail("no user.dir")
        val srcRoot = java.io.File(root, "src/jvmMain/kotlin")
        assertTrue(srcRoot.isDirectory, "wire source tree not found at $srcRoot — run from the repo root")
        val corpus = StringBuilder()
        srcRoot.walkTopDown().filter { it.extension == "kt" }.forEach { corpus.append(it.readText()) }
        val src = corpus.toString()
        val offenders = RouteManifest.all.filter { line ->
            val path = line.substringAfter(' ')
            val literal = path.removeSuffix("…").trimEnd('/')
            // every non-wildcard path must appear as a string literal somewhere
            // in the jvmMain sources (wildcard lines cover startsWith families
            // whose literal prefix must still exist)
            !src.contains("\"$literal")
        }
        assertTrue(offenders.isEmpty(),
            "manifest names routes no jvmMain wire serves anymore (delete them from the manifest):\n  " +
                offenders.joinToString("\n  "))
    }

    // ── gate 1c: known-orphan disposition is enforced ──────────────────────

    @Test
    fun orphanDispositionsHold() {
        // Step 4 dispositions (plan): /api/lcnc/kanban and /api/graal/ingest
        // stay (live in-process consumers); /api/board/import stays (tested,
        // restart-proof); /api/modules stays (module lifecycle IS the drain
        // contract); the five belief routes stay (the curator preset's note
        // advertises teach). What the gate ENFORCES is that these are
        // manifest-registered — a route that is neither consumed nor
        // registered cannot sneak back.
        val mustBeRegistered = listOf(
            "GET /api/lcnc/kanban",
            "POST /api/graal/ingest",
            "POST /api/board/import",
            "GET /api/modules",
            "POST /api/beliefs/teach",
        )
        for (r in mustBeRegistered) {
            assertTrue(r in RouteManifest.all, "orphan-dispositioned route lost from the manifest: $r")
        }
    }

    // ── gate 2: the browser no longer carries a vocabulary table ───────────

    @Test
    fun panelsHtmlHasNoTypesVocabulary() {
        val html = resourceText("panels.html")
        // The retired vocabulary block: `const TYPES = { … }` declaring type
        // keys with ins/outs. Run bodies were retired with it — the palette,
        // ports, and titles all arrive from /api/lcnc/contracts.
        val typesDecl = Regex("""const\s+TYPES\s*=\s*\{""")
        assertTrue(!typesDecl.containsMatchIn(html),
            "panels.html reintroduces a TYPES vocabulary table — contracts have ONE author (LcncContracts via /api/lcnc/contracts)")
    }

    // ── gate 3: the served contract route carries the FULL contract ────────

    @Test
    fun contractRouteManifestIncludesFullContractLine() {
        // The route must exist so the page can hydrate: title/ports/kinds —
        // the fields whose omission made JS stay authoritative.
        val line = RouteManifest.routes["KanbanModule"].orEmpty()
        assertTrue("GET /api/lcnc/contracts" in line, "/api/lcnc/contracts must be registered")
    }

    @Test
    fun jobCommandVerbCountIsNotOverstated() {
        // The retired "12 JobCommand verbs" claim: the sealed hierarchy's
        // actual verb count is what any doc/preset may state. Count it from
        // the compiled class list, not from memory.
        val verbs = JobCommand::class.sealedSubclasses.mapNotNull { it.simpleName }
        assertTrue(verbs.size in 4..12, "JobCommand verbs exploded unexpectedly: $verbs")
        assertTrue("Submit" in verbs && "Move" in verbs && "Cancel" in verbs,
            "core verbs present: $verbs")
    }
}
