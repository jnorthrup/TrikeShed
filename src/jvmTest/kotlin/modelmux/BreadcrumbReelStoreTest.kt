package modelmux

import borg.trikeshed.graal.ConfixBlackboard
import borg.trikeshed.forge.server.BreadcrumbReelService
import borg.trikeshed.job.*
import borg.trikeshed.lib.*
import borg.trikeshed.userspace.nio.file.spi.JvmFileOperations
import kotlinx.coroutines.runBlocking
import keymux.KeyMux
import keymux.LlmAuth
import kotlin.test.*

class BreadcrumbReelStoreTest {
    fun run(id: String, status: String = "completed") = mapOf(
        "id" to id, "startedAt" to 10L, "status" to status,
        "harness" to BreadcrumbReelStore.HARNESS,
        "models" to mapOf("main" to "model-a", "summarizer" to "local"),
        "modelVersions" to mapOf("main" to "revision-a", "summarizer" to "local-digest"),
        "lanes" to listOf(mapOf("id" to "main", "status" to "completed", "resultId" to "root", "output" to "summary")),
        "events" to listOf(mapOf("breadcrumbId" to "root", "lane" to "main",
            "parentIds" to listOf("source"), "model" to "model-a")),
        "assessment" to mapOf("qualification" to BreadcrumbReel.ASSESSMENT_QUALIFICATION,
            "status" to "completed", "content" to "Model preference only"),
    )

    @Test fun votesRejectMissingOutputsAndTiesWithOnlyOneCompletedCandidate() = runBlocking {
        val backing = linkedMapOf<String, String>()
        val persistence = object : BreadcrumbStore {
            override suspend fun load(): Series<Join<String, String>> =
                backing.entries.map { it.key j it.value }.toSeries()
            override suspend fun write(id: String, state: String?) {
                if (state == null) backing.remove(id) else backing[id] = state
            }
        }
        val archive = BreadcrumbReelStore(persistence, CasStore.inMemory())
        val complete = mapOf("id" to "full", "status" to "completed", "resultId" to "full-root", "output" to "Full summary")
        val failed = mapOf("id" to "main", "status" to "failed")
        val blank = mapOf("id" to "micro", "status" to "completed", "resultId" to "micro-root", "output" to " ")
        val partial = run("partial", "partial") + ("lanes" to listOf(complete, failed, blank))
        archive.save(partial)
        for (winner in listOf("main", "micro", "tie")) {
            assertFailsWith<IllegalArgumentException> { archive.vote("partial", winner, "") }
        }
        assertEquals(0, archive.rows(archive.read("partial")!!["votes"]).size)
        archive.vote("partial", "full", "Only completed candidate")
        val missingId = blank + mapOf("output" to "Micro summary", "resultId" to "")
        archive.save(partial + ("lanes" to listOf(complete, failed, missingId)))
        assertFailsWith<IllegalArgumentException> { archive.vote("partial", "micro", "") }
        archive.save(partial + ("lanes" to listOf(complete, failed, blank + ("output" to "Micro summary"))))
        val reopened = BreadcrumbReelStore(persistence, CasStore.inMemory())
        reopened.vote("partial", "tie", "Both completed candidates")
        assertEquals(listOf("full", "tie"), reopened.rows(reopened.read("partial")!!["votes"]).map { it["winner"] })
    }

    @Test fun reopenRestoresPayloadsReviewsAndRoutingWithoutChangingAccuracyIntoSelection() = runBlocking {
        val files = JvmFileOperations()
        val directory = files.createTempDir("reel-history")
        val pack = files.resolvePath(directory, "cas")
        val manifests = files.resolvePath(directory, "reels")
        val raw = "fun α() = \"🛞\"\r\n"
        val cid = ContentId.of(raw.encodeToByteArray())
        try {
            PackCasStore(files, pack).use { cas ->
                val archive = BreadcrumbReelStore(BreadcrumbFileStore(files, manifests), cas) { 100L }
                archive.save(run("run-a"))
                assertEquals(cid, archive.capture("run-a", "source", "src/A.kt", raw.encodeToByteArray()))
                archive.capture("run-a", "summary", "root", "summary".encodeToByteArray(), "main")
                archive.vote("run-a", "main", "Useful structure")
                archive.review("run-a", "main", ModelCorrectness.ACCURATE,
                    ModelDisposition.EXPERIMENT_REJECTED, "Accurate but not useful for this experiment", "lifecycle")
                archive.retain(100, 101, 1.0)
                archive.save(run("interrupted", "running"))
            }
            PackCasStore(files, pack).use { cas ->
                val archive = BreadcrumbReelStore(BreadcrumbFileStore(files, manifests), cas) { 200L }
                assertEquals(raw, archive.payload("run-a", cid), "Exact UTF-8 source survives a new CAS and manifest owner")
                val run = assertNotNull(archive.read("run-a"))
                val capture = archive.objectState(run["capture"])
                assertEquals(true, capture["complete"])
                assertEquals(true, capture["durable"])
                assertEquals(1.0, capture["retainedFraction"])
                assertEquals(1, archive.rows(run["votes"]).size)
                assertEquals(1, archive.rows(run["retention"]).size)
                assertEquals(0, archive.selections().size, "Rejected experiments do not become agenda selections")
                val grades = archive.grades()
                assertEquals(ModelCorrectness.ACCURATE, grades[0].correctness)
                val ranked = rankModelProficiency(s_["model-b", "model-a"], grades,
                    BreadcrumbReelStore.HARNESS j BreadcrumbReelStore.TASK,
                    ModelGradeBasis.CORRECTNESS) { it j "revision-a" }
                assertEquals("model-a", ranked[0], "A rejected experiment may still be accurate")
                assertEquals("interrupted", archive.read("interrupted")?.get("status"))
                val board = ConfixBlackboard.empty()
                archive.restore(board)
                assertNotNull(board.get("ace/run/run-a"))
                assertNotNull(board.get("ace/breadcrumb/run-a/main/root"))
                assertNotNull(board.get("ace/assessment/run-a"))
                archive.review("run-a", "main", ModelCorrectness.INACCURATE,
                    ModelDisposition.SELECTED, "Corrected a lifecycle statement", "drain", "human correction")
                val selection = archive.selections()[0]
                assertEquals("human correction", selection["content"])
                assertEquals("INACCURATE", selection["correctness"], "A human correction must not credit the model")
                assertEquals(listOf(cid.value), selection["sourceCids"])
                assertEquals(0, archive.retain(101, 199, 1.0).size, "The retention interval is exact and half-open")
            }
        } finally { files.deleteRecursively(directory) }
    }

    @Test fun failedPublicationNeverAcknowledgesCapturedPayload() = runBlocking {
        val backing = linkedMapOf<String, String>()
        var reject = false
        var synced = false
        val persistence = object : BreadcrumbStore {
            override suspend fun load(): Series<Join<String, String>> =
                backing.entries.map { it.key j it.value }.toSeries()
            override suspend fun write(id: String, state: String?) {
                if (reject) {
                    assertTrue(synced, "CAS must sync before a capture manifest can publish")
                    error("publication failed")
                }
                if (state != null) backing[id] = state
            }
        }
        val cas = object : CasStore() { override fun sync() { synced = true } }
        val archive = BreadcrumbReelStore(persistence, cas) { 100L }
        archive.save(run("run-a"))
        reject = true
        assertFailsWith<IllegalStateException> {
            archive.capture("run-a", "source", "src/A.kt", "exact".encodeToByteArray())
        }
        val state = assertNotNull(archive.read("run-a"))
        assertEquals(0, (archive.objectState(state["capture"])["watermark"] as Number).toInt())
        reject = false
        val reopened = BreadcrumbReelStore(persistence, cas)
        assertEquals(0, (reopened.objectState(reopened.read("run-a")!!["capture"])["watermark"] as Number).toInt())
    }

    @Test fun serviceSelectsKnownRevisionGradesAndRefusesTheirReuseAfterRevisionChange() = runBlocking {
        val files = JvmFileOperations()
        val directory = files.createTempDir("reel-routing")
        try {
            PackCasStore(files, files.resolvePath(directory, "cas")).use { cas ->
                val persistence = BreadcrumbFileStore(files, files.resolvePath(directory, "reels"))
                val archive = BreadcrumbReelStore(persistence, cas)
                archive.save(run("legacy") - "harness")
                val legacy = archive.review("legacy", "main", ModelCorrectness.ACCURATE, ModelDisposition.UNSELECTED, "Exact", "")
                assertEquals("breadcrumb-reel-v1", legacy["harness"])
                fun catalog(revision: String?) = ModelMux(KeyMux {}) {
                    model("model-b", setOf("chat"), baseUrl = "https://fixture.invalid", auth = LlmAuth.NONE, modelVersion = "revision-b")
                    model("model-a", setOf("chat"), baseUrl = "https://fixture.invalid", auth = LlmAuth.NONE, modelVersion = revision)
                    defaultModel("model-b")
                }
                val reopened = BreadcrumbReelStore(persistence, cas)
                val service = BreadcrumbReelService(this, coroutineContext, ConfixBlackboard.empty(),
                    directory, { catalog("revision-a") }, files = files, archive = reopened)
                service.restoration.await()
                assertEquals("model-b", service.rankedModel(catalog("revision-a")), "Old harness grades do not rank the new harness")
                reopened.save(run("graded"))
                reopened.review("graded", "main", ModelCorrectness.ACCURATE, ModelDisposition.UNSELECTED, "Exact", "")
                assertEquals("model-a", service.rankedModel(catalog("revision-a")))
                assertEquals("model-b", service.rankedModel(catalog("revision-changed")),
                    "A prior revision's review must not credit changed weights")
                assertEquals("model-b", service.rankedModel(catalog(null)),
                    "An opaque revision remains ungraded")
            }
        } finally { files.deleteRecursively(directory) }
    }
}
