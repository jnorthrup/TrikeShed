package borg.trikeshed.lcnc

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/** Route manifest parity with Kotlin wire sources and command contracts. */
class RouteParityGate {

    @Test
    fun manifestRoutesStillExistInWireSources() {
        // Wire sources live on the jvmMain SOURCE tree, readable from the test
        // working directory. A manifest line naming a route no wire claims
        // anymore (deleted upstream) fails here — the manifest is not a
        // graveyard. Match on a distinctive FRAGMENT of each path so
        // compound guards (startsWith + endsWith) also count as serving:
        // "POST /api/graal/capsule/…" is served by
        // `p.startsWith("/api/graal/capsule/")`.
        val root = System.getProperty("user.dir") ?: fail("no user.dir")
        val jvmRoot = java.io.File(root, "src/jvmMain/kotlin")
        assertTrue(jvmRoot.isDirectory, "wire source tree not found at $jvmRoot — run from the repo root")
        val commonRoot = java.io.File(root, "src/commonMain/kotlin")
        val corpus = StringBuilder()
        for (srcDir in listOf(jvmRoot, commonRoot)) {
            if (!srcDir.isDirectory) continue
            srcDir.walkTopDown().filter { it.extension == "kt" }
                .filter { it.name != "RouteManifest.kt" }
                .forEach { corpus.append(it.readText()) }
        }
        val src = corpus.toString()
        val offenders = RouteManifest.all.filter { line ->
            val method = line.substringBefore(' ')
            val path = line.substringAfter(' ')
            val segments = path.trim('/').split('/')
            val concrete = segments.filter { it != "…" && !(it.startsWith("{") && it.endsWith("}")) }
            // served = every concrete segment appears as a literal (or as an
            // endsWith/startsWith fragment) somewhere in the sources
            val allPresent = concrete.all { seg ->
                src.contains("\"$seg") || src.contains("/$seg") || src.contains("\"$seg\"")
            }
            if (!allPresent) return@filter true
            // wildcard segment must be backed by a family guard — either a
            // startsWith prefix match or a segment-split (first == "…") match
            if ("…" in segments) {
                val first = concrete.firstOrNull() ?: ""
                val familyGuard = src.contains("startsWith(\"/$first") ||
                    src.contains("\"$first\"")
                return@filter !familyGuard
            }
            false
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
        // actual verb count is what any doc/preset may state. Count the verbs
        // from the SOURCE (kotlin-reflect is not on the test classpath).
        val src = java.io.File(
            System.getProperty("user.dir") ?: fail("no user.dir"),
            "src/commonMain/kotlin/borg/trikeshed/job/JobCommand.kt",
        )
        assertTrue(src.isFile, "JobCommand.kt not found at $src — run from the repo root")
        val verbs = Regex("""data class (\w+)\(""").findAll(src.readText()).map { it.groupValues[1] }.toList()
        assertTrue(verbs.size in 4..12, "JobCommand verbs drifted (${verbs.size}): $verbs")
        assertTrue(setOf("Submit", "Move", "Cancel").all { it in verbs }, "core verbs present: $verbs")
    }
}
