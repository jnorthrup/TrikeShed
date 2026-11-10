package borg.trikeshed.forge.server

import borg.trikeshed.job.ContentId
import borg.trikeshed.graal.ConfixBlackboard
import borg.trikeshed.lcnc.*
import borg.trikeshed.lib.*
import borg.trikeshed.narsese.ConstellationNodes
import borg.trikeshed.narsese.DecisionProvider
import borg.trikeshed.narsese.DocumentSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CurationIntakeTest {
    private class Corpus(private val documents: List<String> = emptyList()) : ProjectCorpus {
        val extracted = mutableListOf<String>()
        override suspend fun projects(): List<ProjectRef> = if (documents.isEmpty()) emptyList() else
            listOf(ProjectRef("project", "assets", "@upload", documents.size))
        override suspend fun docs(project: String, prefix: String, glob: String, limit: Int): List<ProjectDoc> =
            documents.map { ProjectDoc(project, it, ContentId.of(it.encodeToByteArray()).value, "1", 0, it.length.toLong(), "text/plain") }
        override suspend fun read(project: String, id: String, maxChars: Int): ProjectText? = null
        override suspend fun extract(project: String, id: String, maxChars: Int): ProjectText {
            extracted.add(id)
            return ProjectText(project, id, ContentId.of(id.encodeToByteArray()).value, "1", 0, id)
        }
    }

    @Test
    fun reviewIntakeSharesReducedProfileBookIdentityWithHostNodes(): Unit = runBlocking {
        val home = Files.createTempDirectory("profile-review-intake").toFile()
        val nodes = ConstellationNodes(home, ConfixBlackboard(), scope = this,
            corpusParserProfile = borg.trikeshed.graal.subvm.CoreNlpRuntime.Profile.NO_KBP)
        val ontology = ContentId.of("ontology".encodeToByteArray())
        val text = "Staff must deliver notice."
        val cid = ContentId.of(text.encodeToByteArray())
        val source = DocumentSource(cid, cid, text, "one.pdf", "text/plain", "profile-test")
        var selectedBook: String? = null
        var joined = 0
        val runners = mapOf(
            ConstellationNodes.CURATE to LcncNodeRunner { node, _ ->
                val book = nodes.curationBookName(node.params.getValue("book"), ontology)
                selectedBook = book
                mapOf("book" to book, "curationReceiptCid" to ContentId.of("receipt".encodeToByteArray()).value,
                    "ontologyCid" to ontology.value, "reviewStatus" to "unreviewed")
            },
            ConstellationNodes.JOIN to LcncNodeRunner { _, _ -> joined++; emptyMap() })
        val intake = CurationIntake(Corpus(), { runners }, { nodes }, this, settled = {},
            retainedSource = { _, _ -> source }, mode = { _, _ -> CurationIntake.Mode.ReviewRequired(ontology) })
        try {
            intake.offer("project", "one.pdf")
            intake.drain()
            val tally = intake.status().getValue("project") as Map<*, *>
            assertEquals(0, tally["failed"])
            assertEquals(1, tally["awaitingReview"])
            assertTrue(requireNotNull(selectedBook).contains("@parser-no-kbp-v1-"))
            val awaiting = (tally["awaitingReviews"] as List<*>).single() as Map<*, *>
            assertEquals(selectedBook, awaiting["book"])
            assertEquals(0, joined)
        } finally { nodes.drain(); home.deleteRecursively() }
    }

    @Test
    fun selectedProjectTextIsSourceBoundAndAwaitingReviewWithoutAutomaticAdmission(): Unit = runBlocking {
        val cas = borg.trikeshed.job.CasStore.inMemory()
        val store = borg.trikeshed.couch.CouchStoreFactory.casBacked(cas)
        val gateway = borg.trikeshed.util.oroboros.CouchAttachmentGateway(store, cas)
        val index = borg.trikeshed.memory.CouchIndexBridge(gateway,
            borg.trikeshed.memory.MemoryIndexLayer(borg.trikeshed.memory.MemoryStore(cas, store)))
        val registry = ProjectDbRegistry("primary")
        val home = Files.createTempDirectory("selected-text-review").toFile()
        val scopes = ProjectScopes(borg.trikeshed.userspace.nio.file.spi.JvmFileOperations(), gateway, index, cas, null,
            projectDbs = registry, ledgerFile = File(home, "projects.tsv"), filesRoot = File(home, "files"))
        val project = scopes.beginUpload("legal").name
        val corpus = JvmProjectCorpus(registry, scopes)
        val ontology = ContentId.of("ontology".encodeToByteArray())
        val receipt = ContentId.of("receipt".encodeToByteArray())
        var selectedMode: CurationIntake.Mode = CurationIntake.Mode.ReviewRequired(ontology)
        val received = mutableListOf<Map<String, Any?>>()
        var joined = 0
        val runners = mapOf(
            ConstellationNodes.CURATE to LcncNodeRunner { node, inputs ->
                received.add(inputs)
                mapOf("book" to (node.params.getValue("book") + "@" + ontology.hex.take(16)),
                    "curationReceiptCid" to receipt.value, "ontologyCid" to ontology.value, "reviewStatus" to "unreviewed")
            },
            ConstellationNodes.JOIN to LcncNodeRunner { _, _ -> joined++; emptyMap() },
        )
        val intake = CurationIntake(corpus, { runners }, { null }, this,
            retainedSource = { p, id -> DocumentCurationLegos.projectSource(cas, corpus, requireNotNull(corpus.document(p, id))) },
            mode = { _, _ -> selectedMode })
        scopes.onDocument = { p, id, provider -> intake.offer(p, id, provider) }
        scopes.onTextForReview = { selected -> intake.offerTextForReview(selected) }
        try {
            val document = "Notice must be delivered in writing."
            val cid = scopes.uploadPut(project, "agreement.txt", document.encodeToByteArray())
            scopes.uploadPut(project, "guide.md", "A second text document".encodeToByteArray())
            scopes.uploadPut(project, "data.csv", "party,notice".encodeToByteArray())
            scopes.uploadPut(project, "code.kt", "fun main() {}".encodeToByteArray())
            scopes.uploadPut(project, "agreement.txt.notes.md", "notes".encodeToByteArray())
            intake.resume()
            assertTrue(intake.status().isEmpty(), "upload and resume must not auto-admit text or data")
            assertFailsWith<IllegalArgumentException> { scopes.admitDocument(project, "data.csv") }
            assertFailsWith<IllegalArgumentException> { scopes.admitDocument(project, "code.kt") }
            assertFailsWith<IllegalArgumentException> { scopes.admitDocument(project, "agreement.txt.notes.md") }
            selectedMode = CurationIntake.Mode.Legacy
            assertFailsWith<IllegalArgumentException> { scopes.admitDocument(project, "agreement.txt") }
            selectedMode = CurationIntake.Mode.Blocked("unavailable")
            assertFailsWith<IllegalArgumentException> { scopes.admitDocument(project, "agreement.txt") }
            selectedMode = CurationIntake.Mode.ReviewRequired(ontology)
            scopes.admitDocument(project, "agreement.txt")
            intake.drain()
            assertEquals(1, received.size)
            val source = received.single().getValue("source") as Map<*, *>
            assertEquals(cid.value, source["originalCid"])
            assertEquals(cid.value, source["extractedTextCid"])
            assertEquals(document, source["text"])
            assertEquals(ontology.value, received.single()["ontologyCid"])
            assertEquals(0, joined)
            val tally = intake.status().getValue(project) as Map<*, *>
            assertEquals(1, tally["awaitingReview"])
            assertEquals(0, tally["curated"])
            assertEquals(0, tally["failed"])
        } finally {
            intake.drain()
            scopes.unmount(project)
            scopes.drain()
            home.deleteRecursively()
        }
    }

    @Test
    fun replacedSelectedTextCannotBeCuratedAsTheSelectedRevision(): Unit = runBlocking {
        val cas = borg.trikeshed.job.CasStore.inMemory()
        val store = borg.trikeshed.couch.CouchStoreFactory.casBacked(cas)
        val gateway = borg.trikeshed.util.oroboros.CouchAttachmentGateway(store, cas)
        val index = borg.trikeshed.memory.CouchIndexBridge(gateway,
            borg.trikeshed.memory.MemoryIndexLayer(borg.trikeshed.memory.MemoryStore(cas, store)))
        val registry = ProjectDbRegistry("primary")
        val home = Files.createTempDirectory("selected-text-replaced").toFile()
        val scopes = ProjectScopes(borg.trikeshed.userspace.nio.file.spi.JvmFileOperations(), gateway, index, cas, null,
            projectDbs = registry, ledgerFile = File(home, "projects.tsv"), filesRoot = File(home, "files"))
        val project = scopes.beginUpload("legal").name
        val corpus = JvmProjectCorpus(registry, scopes)
        val ontology = ContentId.of("ontology".encodeToByteArray())
        var retained = 0
        var curated = 0
        val runners = mapOf(ConstellationNodes.CURATE to LcncNodeRunner { _, _ -> curated++; emptyMap() })
        val intake = CurationIntake(corpus, { runners }, { null }, this,
            retainedSource = { p, id ->
                retained++
                DocumentCurationLegos.projectSource(cas, corpus, requireNotNull(corpus.document(p, id)))
            }, mode = { _, _ -> CurationIntake.Mode.ReviewRequired(ontology) })
        scopes.onDocument = { p, id, provider -> intake.offer(p, id, provider) }
        scopes.onTextForReview = { selected -> intake.offerTextForReview(selected) }
        try {
            scopes.uploadPut(project, "agreement.txt", "Selected version A".encodeToByteArray())
            scopes.admitDocument(project, "agreement.txt")
            // No suspension has occurred since admission, so the queued worker has not read it.
            scopes.uploadPut(project, "agreement.txt", "Replacement version B".encodeToByteArray())
            intake.drain()
            assertEquals(0, retained, "replacement must be detected before source retention")
            assertEquals(0, curated)
            val tally = intake.status().getValue(project) as Map<*, *>
            assertEquals(1, tally["failed"])
            assertEquals(0, tally["awaitingReview"])
            assertTrue((tally["note"] as String).contains("revision changed"))
        } finally {
            intake.drain()
            scopes.unmount(project)
            scopes.drain()
            home.deleteRecursively()
        }
    }

    @Test
    fun blockedCurationBoundsExtractedDocumentsAndDrainFinishesEachAdmission(): Unit = runBlocking {
        val corpus = Corpus()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val curated = mutableListOf<String>()
        val joined = mutableListOf<String>()
        val runners = mapOf(
            ConstellationNodes.CURATE to LcncNodeRunner { _, inputs ->
                started.complete(Unit)
                release.await()
                val id = (inputs.getValue("doc") as Map<*, *>)["id"] as String
                curated.add(id)
                mapOf("book" to id, "statements" to 1, "sections" to 1)
            },
            ConstellationNodes.JOIN to LcncNodeRunner { _, inputs ->
                joined.add(inputs.getValue("book") as String)
                emptyMap()
            },
        )
        val intake = CurationIntake(corpus, { runners }, { null }, this)
        val ids = List(CurationIntake.DOCUMENTS * 4) { "book-$it.pdf" }
        for (id in ids) { intake.offer("project", id); intake.offer("project", id) }
        intake.offer("project", "book.pdf${ProjectNodes.EXTRACT_SUFFIX}")
        try {
            withTimeout(5_000) {
                started.await()
                // All runnable admissions may advance; only the parser's deferred remains blocked.
                repeat(ids.size * 3) { yield() }
            }
            assertTrue(corpus.extracted.size <= CurationIntake.DOCUMENTS,
                "${corpus.extracted.size} decoded documents accumulated behind one blocked parser")
            assertEquals(CurationIntake.DOCUMENTS, corpus.extracted.size,
                "a startup backlog must wake each bounded extraction consumer")
            val draining = async { intake.drain() }
            yield()
            assertFalse(draining.isCompleted, "drain must join the admitted parse")
            assertFailsWith<IllegalStateException> { intake.offer("project", "after.pdf") }
            release.complete(Unit)
            withTimeout(5_000) { draining.await() }

            assertEquals(ids.sorted(), corpus.extracted.sorted())
            assertEquals(ids.sorted(), curated.sorted())
            assertEquals(ids.sorted(), joined.sorted())
            val status = intake.status().getValue("project") as Map<*, *>
            assertEquals(ids.size, status["queued"])
            assertEquals(ids.size, status["curated"])
            assertEquals(0, status["pending"])
            assertEquals(0, status["failed"])
        } finally {
            release.complete(Unit)
            intake.drain()
        }
    }

    @Test
    fun forgottenQueuedDocumentIsReleasedWithoutExtraction(): Unit = runBlocking {
        val corpus = Corpus()
        val runners = mapOf(
            ConstellationNodes.CURATE to LcncNodeRunner { _, inputs -> mapOf("book" to inputs.getValue("text")) },
            ConstellationNodes.JOIN to LcncNodeRunner { _, _ -> emptyMap() },
        )
        val intake = CurationIntake(corpus, { runners }, { null }, this)
        intake.offer("project", "held.pdf")
        intake.offer("project", "removed.pdf")
        intake.forget("project", "removed.pdf")
        intake.drain()

        assertEquals(listOf("held.pdf"), corpus.extracted)
        val status = intake.status().getValue("project") as Map<*, *>
        assertEquals(1, status["queued"])
        assertEquals(1, status["curated"])
        assertEquals(0, status["pending"])
        assertEquals(0, status["failed"])
    }

    @Test
    fun selectedProviderIsFrozenPerAdmissionAndDrainWaitsForScoring(): Unit = runBlocking {
        val corpus = Corpus()
        var selected = DecisionProvider.JEV
        val scored = mutableListOf<Pair<DecisionProvider, String>>()
        val dispatched = mutableListOf<String?>()
        val events = mutableListOf<String>()
        var releases = 0
        val scoring = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val runners = mapOf(
            ConstellationNodes.CURATE to LcncNodeRunner { node, inputs ->
                events.add("curate")
                dispatched.add(node.params["decisionProvider"])
                mapOf("book" to inputs.getValue("text"))
            },
            ConstellationNodes.JOIN to LcncNodeRunner { node, _ ->
                events.add("join")
                dispatched.add(node.params["decisionProvider"])
                emptyMap()
            },
        )
        val intake = CurationIntake(corpus, { runners }, { null }, this, { _, _ -> selected },
            settled = { releases++; events.add("release") }, score = { provider, book ->
            scoring.complete(Unit)
            release.await()
            scored.add(provider to book)
            events.add("score")
            if (provider == DecisionProvider.OPENAI) error("provider unavailable")
        })
        intake.offer("project", "book.pdf")
        intake.offer("project", "book.pdf")
        selected = DecisionProvider.OPENAI
        intake.offer("project", "book.pdf")
        val draining = async { intake.drain() }
        try {
            withTimeout(5_000) { scoring.await() }
            assertFalse(draining.isCompleted)
            assertEquals(0, releases, "inference must remain owned while either book is pending")
            val pending = intake.status().getValue("project") as Map<*, *>
            assertEquals(0, pending["curated"], "parsing alone is not completed provider curation")
            release.complete(Unit)
            withTimeout(5_000) { draining.await() }
            assertEquals(listOf(DecisionProvider.JEV to "book.pdf", DecisionProvider.OPENAI to "book.pdf"), scored)
            assertEquals(listOf<String?>("jev", "jev", "jev", DecisionProvider.OPENAI.id, DecisionProvider.OPENAI.id), dispatched)
            assertEquals(listOf("curate", "join", "score", "join", "curate", "join", "score", "release"), events,
                "only successful judgments can publish the constellation again")
            assertEquals(1, releases, "release follows the final successful or failed book")
            val status = intake.status().getValue("project") as Map<*, *>
            assertEquals(2, status["queued"])
            assertEquals(1, status["curated"])
            assertEquals(1, status["failed"])
            assertEquals(0, status["pending"])
            val providers = status["providers"] as Map<*, *>
            assertEquals(1, (providers["jev"] as Map<*, *>)["curated"])
            assertEquals(1, (providers[DecisionProvider.OPENAI.id] as Map<*, *>)["failed"])
        } finally {
            release.complete(Unit)
            intake.drain()
        }
    }

    @Test
    fun resumedDocumentsUseRetainedProviderAndLegacyDocumentsDoNotScore(): Unit = runBlocking {
        val corpus = Corpus(listOf("legacy.pdf", "selected.pdf"))
        val scored = mutableListOf<Pair<DecisionProvider, String>>()
        val runners = mapOf(
            ConstellationNodes.CURATE to LcncNodeRunner { _, inputs -> mapOf("book" to inputs.getValue("text")) },
            ConstellationNodes.JOIN to LcncNodeRunner { _, _ -> emptyMap() },
        )
        val intake = CurationIntake(corpus, { runners }, { null }, this,
            { _, id -> if (id == "selected.pdf") DecisionProvider.OPENAI else null },
            score = { provider, book -> scored.add(provider to book) })
        intake.resume()
        intake.drain()
        assertEquals(listOf(DecisionProvider.OPENAI to "selected.pdf"), scored)
        assertEquals(listOf("legacy.pdf", "selected.pdf"), corpus.extracted)
        assertEquals(2, (intake.status().getValue("project") as Map<*, *>)["curated"])
    }

    @Test
    fun reviewIntakeRetainsSourceAndReceiptWithoutPublishing(): Unit = runBlocking {
        val corpus = Corpus()
        val source = DocumentSource(ContentId.of("original".encodeToByteArray()),
            ContentId.of("extracted text".encodeToByteArray()), "extracted text", "agreement.pdf",
            "application/pdf", "test")
        val ontology = ContentId.of("ontology".encodeToByteArray())
        val receipt = ContentId.of("receipt".encodeToByteArray())
        var selected: CurationIntake.Mode = CurationIntake.Mode.ReviewRequired(ontology)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val calls = mutableListOf<String>()
        val received = mutableMapOf<String, Any?>()
        var expectedBook = ""
        val runners = mapOf(
            ConstellationNodes.CURATE to LcncNodeRunner { node, inputs ->
                calls.add("curate")
                received.putAll(inputs)
                expectedBook = node.params.getValue("book") + "@" + ontology.hex.take(16)
                started.complete(Unit)
                release.await()
                mapOf("book" to expectedBook, "curationReceiptCid" to receipt.value,
                    "ontologyCid" to ontology.value, "reviewStatus" to "unreviewed")
            },
            ConstellationNodes.JOIN to LcncNodeRunner { _, _ -> calls.add("join"); emptyMap() },
            ConstellationNodes.BRIEF to LcncNodeRunner { _, _ -> calls.add("brief"); emptyMap() },
        )
        val intake = CurationIntake(corpus, { runners }, { null }, this,
            selectedProvider = { _, _ -> DecisionProvider.JEV },
            grab = { calls.add("grab") },
            retainedSource = { project, id ->
                assertEquals("project", project)
                assertEquals("agreement.pdf", id)
                source
            },
            mode = { _, _ -> selected },
            score = { _, _ -> calls.add("score") })
        intake.offer("project", "agreement.pdf")
        selected = CurationIntake.Mode.Legacy
        val draining = async { intake.drain() }
        try {
            withTimeout(5_000) { started.await() }
            assertFalse(draining.isCompleted)
            val pending = intake.status().getValue("project") as Map<*, *>
            assertEquals(1, pending["pending"])
            assertEquals(0, pending["awaitingReview"])
            release.complete(Unit)
            withTimeout(5_000) { draining.await() }

            assertEquals(listOf("curate"), calls)
            assertTrue(corpus.extracted.isEmpty())
            assertEquals(ontology.value, received["ontologyCid"])
            val retained = received["source"] as Map<*, *>
            assertEquals(source.originalCid.value, retained["originalCid"])
            assertEquals(source.extractedTextCid.value, retained["extractedTextCid"])
            assertEquals(source.text, retained["text"])
            val status = intake.status().getValue("project") as Map<*, *>
            assertEquals(1, status["queued"])
            assertEquals(1, status["awaitingReview"])
            assertEquals(0, status["curated"])
            assertEquals(0, status["failed"])
            assertEquals(0, status["pending"])
            assertTrue((status["providers"] as Map<*, *>).isEmpty())
            assertEquals(listOf(mapOf("document" to "agreement.pdf", "book" to expectedBook,
                "curationReceiptCid" to receipt.value, "ontologyCid" to ontology.value,
                "reviewStatus" to "unreviewed", "reviewStatusObservedAt" to "intake")), status["awaitingReviews"])
        } finally {
            release.complete(Unit)
            intake.drain()
        }
    }

    @Test
    fun reviewIntakeFailsWithoutRetainedSourceRatherThanExtractingOrPublishing(): Unit = runBlocking {
        val corpus = Corpus()
        val calls = mutableListOf<String>()
        val runners = mapOf(
            ConstellationNodes.CURATE to LcncNodeRunner { _, _ -> calls.add("curate"); emptyMap() },
            ConstellationNodes.JOIN to LcncNodeRunner { _, _ -> calls.add("join"); emptyMap() },
            ConstellationNodes.BRIEF to LcncNodeRunner { _, _ -> calls.add("brief"); emptyMap() },
        )
        val intake = CurationIntake(corpus, { runners }, { null }, this,
            mode = { _, _ -> CurationIntake.Mode.ReviewRequired(ContentId.of("ontology".encodeToByteArray())) })
        intake.offer("project", "agreement.pdf")
        intake.drain()
        assertTrue(corpus.extracted.isEmpty())
        assertTrue(calls.isEmpty())
        val status = intake.status().getValue("project") as Map<*, *>
        assertEquals(1, status["failed"])
        assertEquals(0, status["curated"])
        assertEquals(0, status["awaitingReview"])
        assertEquals(0, status["pending"])
        assertTrue((status["awaitingReviews"] as List<*>).isEmpty())
    }

    @Test
    fun reviewIntakeRejectsAResultWithoutAnUnreviewedReceipt(): Unit = runBlocking {
        val source = DocumentSource(ContentId.of("original".encodeToByteArray()),
            ContentId.of("text".encodeToByteArray()), "text", "agreement.pdf", "application/pdf", "test")
        val ontology = ContentId.of("ontology".encodeToByteArray())
        val receipt = ContentId.of("receipt".encodeToByteArray())
        val badResults = listOf(
            mapOf("ontologyCid" to ontology.value, "reviewStatus" to "unreviewed"),
            mapOf("curationReceiptCid" to receipt.value,
                "ontologyCid" to ontology.value, "reviewStatus" to "reviewed"),
            mapOf("curationReceiptCid" to receipt.value,
                "ontologyCid" to ContentId.of("other ontology".encodeToByteArray()).value, "reviewStatus" to "unreviewed"),
            mapOf("book" to "wrong-book", "curationReceiptCid" to receipt.value,
                "ontologyCid" to ontology.value, "reviewStatus" to "unreviewed"),
        )
        for (result in badResults) {
            val calls = mutableListOf<String>()
            val runners = mapOf(
                ConstellationNodes.CURATE to LcncNodeRunner { node, _ ->
                    calls.add("curate")
                    mapOf("book" to (node.params.getValue("book") + "@" + ontology.hex.take(16))) + result
                },
                ConstellationNodes.JOIN to LcncNodeRunner { _, _ -> calls.add("join"); emptyMap() },
                ConstellationNodes.BRIEF to LcncNodeRunner { _, _ -> calls.add("brief"); emptyMap() },
            )
            val intake = CurationIntake(Corpus(), { runners }, { null }, this,
                retainedSource = { _, _ -> source },
                mode = { _, _ -> CurationIntake.Mode.ReviewRequired(ontology) })
            intake.offer("project", "agreement.pdf")
            intake.drain()
            assertEquals(listOf("curate"), calls)
            val status = intake.status().getValue("project") as Map<*, *>
            assertEquals(1, status["failed"])
            assertEquals(0, status["awaitingReview"])
            assertEquals(0, status["curated"])
            assertEquals(0, status["pending"])
            assertTrue((status["awaitingReviews"] as List<*>).isEmpty())
        }
    }

    @Test
    fun replacementUploadWaitsUntilTheRemovalHookHasFinished(): Unit = runBlocking {
        val cas = borg.trikeshed.job.CasStore.inMemory()
        val store = borg.trikeshed.couch.CouchStoreFactory.casBacked(cas)
        val gateway = borg.trikeshed.util.oroboros.CouchAttachmentGateway(store, cas)
        val index = borg.trikeshed.memory.CouchIndexBridge(gateway,
            borg.trikeshed.memory.MemoryIndexLayer(borg.trikeshed.memory.MemoryStore(cas, store)))
        val registry = ProjectDbRegistry("primary")
        val home = Files.createTempDirectory("intake-remove-order").toFile()
        val scopes = ProjectScopes(borg.trikeshed.userspace.nio.file.spi.JvmFileOperations(), gateway, index, cas, null,
            projectDbs = registry, ledgerFile = File(home, "projects.tsv"), filesRoot = File(home, "files"))
        val project = scopes.beginUpload("legal").name
        scopes.uploadPut(project, "agreement.pdf", "old".encodeToByteArray())
        val entered = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val replacementStarted = CompletableDeferred<Unit>()
        val events = java.util.concurrent.CopyOnWriteArrayList<String>()
        scopes.onRemoved = { _, _ ->
            events.add("remove-start")
            entered.complete(Unit)
            check(release.await(5, TimeUnit.SECONDS)) { "removal hook was not released" }
            events.add("remove-end")
        }
        scopes.onDocument = { _, _, _ -> events.add("replacement") }
        val removing = async(Dispatchers.IO) { scopes.uploadRemove(project, "agreement.pdf") }
        try {
            withTimeout(5_000) { entered.await() }
            val replacing = async(Dispatchers.IO) {
                replacementStarted.complete(Unit)
                scopes.uploadPut(project, "agreement.pdf", "new".encodeToByteArray())
            }
            withTimeout(5_000) { replacementStarted.await() }
            val overtook = withTimeoutOrNull(1_000) { replacing.await(); true } ?: false
            release.countDown()
            withTimeout(5_000) { removing.await(); replacing.await() }
            assertFalse(overtook, "replacement admission overtook the earlier removal callback")
            assertEquals(_l["remove-start", "remove-end", "replacement"], events.toList())
            assertContentEquals("new".encodeToByteArray(), registry.get(project)?.gateway?.getAttachment("agreement.pdf")?.second)
        } finally {
            release.countDown()
            removing.await()
            scopes.unmount(project)
            scopes.drain()
            home.deleteRecursively()
        }
    }

    @Test
    fun internallyCancelledJoinSettlesFailureAndKeepsTheConsumerAvailable(): Unit = runBlocking {
        var joins = 0
        val runners = _m[
            ConstellationNodes.CURATE j LcncNodeRunner { _, inputs ->
                val doc = inputs.getValue("doc") as Map<*, *>
                _m["book" j (doc["id"] as String)]
            },
            ConstellationNodes.JOIN j LcncNodeRunner { _, _ ->
                joins++
                if (joins == 1) throw CancellationException("provider timed out")
                emptyMap()
            },
        ]
        val intake = CurationIntake(Corpus(), { runners }, { null }, this)
        intake.offer("project", "first.pdf")
        intake.offer("project", "second.pdf")
        withTimeout(5_000) { intake.drain() }
        val status = intake.status().getValue("project") as Map<*, *>
        assertEquals(2, joins)
        assertEquals(2, status["queued"])
        assertEquals(1, status["failed"])
        assertEquals(1, status["curated"])
        assertEquals(0, status["pending"])
        assertTrue((status["note"] as String).contains("provider timed out"))
    }

    @Test
    fun concurrentDrainsWaitForCancelledWorkToReleaseInference(): Unit = runBlocking {
        val owner = SupervisorJob(coroutineContext[Job])
        val scope = CoroutineScope(owner + Dispatchers.Default)
        val started = Channel<String>(Channel.UNLIMITED)
        val releaseStarted = CompletableDeferred<Unit>()
        val releaseGate = CompletableDeferred<Unit>()
        val intake = CurationIntake(Corpus(), { emptyMap() }, { null }, scope,
            settled = { releaseStarted.complete(Unit); releaseGate.await() },
            retainedSource = { _, id -> started.send(id); awaitCancellation() })
        try {
            repeat(CurationIntake.DOCUMENTS + 1) { intake.offer("project", "book-$it.pdf") }
            repeat(CurationIntake.DOCUMENTS) { withTimeout(5_000) { started.receive() } }
            owner.cancel()
            val first = async { intake.drain() }
            withTimeout(5_000) { releaseStarted.await() }
            val secondStarted = CompletableDeferred<Unit>()
            val second = async { secondStarted.complete(Unit); intake.drain() }
            withTimeout(5_000) { secondStarted.await() }
            val early = withTimeoutOrNull(500) { second.await(); true } ?: false
            releaseGate.complete(Unit)
            withTimeout(5_000) { first.await(); second.await() }
            assertFalse(early, "every drain must wait for the inference release")
            val status = intake.status().getValue("project") as Map<*, *>
            assertEquals(0, status["pending"])
            assertEquals(CurationIntake.DOCUMENTS + 1, status["failed"])
        } finally {
            releaseGate.complete(Unit)
            owner.cancelAndJoin()
        }
    }

    @Test
    fun ownerCancellationSettlesActiveQueuedAndFollowUpAdmissions(): Unit = runBlocking {
        val owner = SupervisorJob(coroutineContext[Job])
        val scope = CoroutineScope(owner + Dispatchers.Default)
        val started = Channel<String>(Channel.UNLIMITED)
        val released = java.util.concurrent.atomic.AtomicInteger()
        val intake = CurationIntake(Corpus(), { emptyMap() }, { null }, scope,
            settled = { released.incrementAndGet(); Unit },
            retainedSource = { _, id -> started.send(id); awaitCancellation() })
        try {
            val count = CurationIntake.DOCUMENTS + 2
            repeat(count) { intake.offer("project", "book-$it.pdf", DecisionProvider.JEV) }
            val first = withTimeout(5_000) { started.receive() }
            repeat(CurationIntake.DOCUMENTS - 1) { withTimeout(5_000) { started.receive() } }
            intake.offer("project", first, DecisionProvider.JEV)
            owner.cancel()
            withTimeout(5_000) { intake.drain() }
            val status = intake.status().getValue("project") as Map<*, *>
            assertTrue(owner.isCancelled)
            assertEquals(count + 1, status["queued"])
            assertEquals(count + 1, status["failed"])
            assertEquals(0, status["curated"])
            assertEquals(0, status["pending"])
            assertTrue((status["note"] as String).contains("cancel"))
            val providers = status["providers"] as Map<*, *>
            val provider = providers[DecisionProvider.JEV.id] as Map<*, *>
            assertEquals(count + 1, provider["queued"])
            assertEquals(count + 1, provider["failed"])
            assertEquals(0, provider["pending"])
            assertEquals(1, released.get(), "cancelled queued work must not strand the inference lease")
        } finally { owner.cancelAndJoin() }
    }

    @Test
    fun ownerCancellationDuringRemovedJoinReportsUncertaintyAndPropagates(): Unit = runBlocking {
        val owner = SupervisorJob(coroutineContext[Job])
        val scope = CoroutineScope(owner + Dispatchers.Default)
        val started = CompletableDeferred<Unit>()
        val runners = _m[
            ConstellationNodes.CURATE j LcncNodeRunner { _, _ -> _m["book" j "renamed-book"] },
            ConstellationNodes.JOIN j LcncNodeRunner { _, _ -> started.complete(Unit); awaitCancellation() },
        ]
        val intake = CurationIntake(Corpus(), { runners }, { null }, scope)
        try {
            intake.offer("project", "agreement.pdf")
            withTimeout(5_000) { started.await() }
            intake.forget("project", "agreement.pdf")
            owner.cancel()
            withTimeout(5_000) { intake.drain() }
            val status = intake.status().getValue("project") as Map<*, *>
            assertTrue(owner.isCancelled)
            assertEquals(1, status["failed"])
            assertEquals(0, status["curated"])
            assertEquals(0, status["pending"])
            assertTrue((status["note"] as String).contains("derived membership may be stale"))
        } finally {
            owner.cancelAndJoin()
        }
    }

    @Test
    fun removalDuringJoinReportsUncertainMembershipAndKeepsTheReplacement(): Unit = runBlocking {
        val joinStarted = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var joins = 0
        val runners = _m[
            ConstellationNodes.CURATE j LcncNodeRunner { _, _ -> _m["book" j "renamed-book"] },
            ConstellationNodes.JOIN j LcncNodeRunner { _, _ ->
                joins++
                if (joins == 1) {
                    joinStarted.complete(Unit)
                    release.await()
                }
                emptyMap()
            },
        ]
        val intake = CurationIntake(Corpus(), { runners }, { null }, this)
        intake.offer("project", "agreement.pdf")
        try {
            withTimeout(5_000) { joinStarted.await() }
            intake.forget("project", "agreement.pdf")
            intake.offer("project", "agreement.pdf")
            release.complete(Unit)
            withTimeout(5_000) { intake.drain() }
            assertEquals(2, joins, "the replacement must still be allowed to join")
            val status = intake.status().getValue("project") as Map<*, *>
            assertEquals(2, status["queued"])
            assertEquals(1, status["failed"])
            assertEquals(1, status["curated"])
            assertEquals(0, status["pending"])
            assertTrue((status["note"] as String).contains("derived membership may be stale"))
        } finally {
            release.complete(Unit)
            intake.drain()
        }
    }

    @Test
    fun forgettingDuringLegacyCurationDoesNotJoinADeletedDocument(): Unit = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val joined = mutableListOf<String>()
        val runners = _m[
            ConstellationNodes.CURATE j LcncNodeRunner { _, inputs ->
                started.complete(Unit)
                release.await()
                val doc = inputs.getValue("doc") as Map<*, *>
                _m["book" j (doc["id"] as String)]
            },
            ConstellationNodes.JOIN j LcncNodeRunner { _, inputs ->
                joined.add(inputs.getValue("book") as String)
                emptyMap()
            },
        ]
        val intake = CurationIntake(Corpus(), { runners }, { null }, this)
        intake.offer("project", "agreement.pdf")
        try {
            withTimeout(5_000) { started.await() }
            intake.forget("project", "agreement.pdf")
            release.complete(Unit)
            withTimeout(5_000) { intake.drain() }
            assertTrue(joined.isEmpty(), "a deleted document must not be joined after curation finishes")
            val status = intake.status().getValue("project") as Map<*, *>
            assertEquals(0, status["queued"])
            assertEquals(0, status["curated"])
            assertEquals(0, status["failed"])
            assertEquals(0, status["pending"])
            assertTrue((status["note"] as String).contains("unjoined artifacts may remain"))
        } finally {
            release.complete(Unit)
            intake.drain()
        }
    }

    @Test
    fun forgettingDuringReviewCurationDoesNotPublishAStaleReceipt(): Unit = runBlocking {
        val source = DocumentSource(ContentId.of("original".encodeToByteArray()),
            ContentId.of("text".encodeToByteArray()), "text", "agreement.pdf", "application/pdf", "test")
        val ontology = ContentId.of("ontology".encodeToByteArray())
        val receipt = ContentId.of("receipt".encodeToByteArray())
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val runners = mapOf(ConstellationNodes.CURATE to LcncNodeRunner { node, _ ->
            started.complete(Unit)
            release.await()
            mapOf("book" to node.params.getValue("book") + "@" + ontology.hex.take(16), "curationReceiptCid" to receipt.value,
                "ontologyCid" to ontology.value, "reviewStatus" to "unreviewed")
        })
        val intake = CurationIntake(Corpus(), { runners }, { null }, this,
            retainedSource = { _, _ -> source }, mode = { _, _ -> CurationIntake.Mode.ReviewRequired(ontology) })
        intake.offer("project", "agreement.pdf")
        try {
            withTimeout(5_000) { started.await() }
            intake.forget("project", "agreement.pdf")
            release.complete(Unit)
            withTimeout(5_000) { intake.drain() }
            val status = intake.status().getValue("project") as Map<*, *>
            assertEquals(0, status["queued"])
            assertEquals(0, status["awaitingReview"])
            assertEquals(0, status["pending"])
            assertEquals(0, status["curated"])
            assertTrue((status["awaitingReviews"] as List<*>).isEmpty())
        } finally {
            release.complete(Unit)
            intake.drain()
        }
    }

    @Test
    fun repeatedReviewReplacesObservationAndBookIdentityTracksItsInputs(): Unit = runBlocking {
        val settled = Channel<Unit>(Channel.UNLIMITED)
        val names = mutableListOf<String>()
        val receipt = ContentId.of("receipt".encodeToByteArray())
        var sourceText = "first version"
        var ontology = ContentId.of("ontology one".encodeToByteArray())
        val runners = mapOf(ConstellationNodes.CURATE to LcncNodeRunner { node, inputs ->
            names.add(node.params.getValue("book"))
            mapOf("book" to node.params.getValue("book") + "@" + ContentId(inputs.getValue("ontologyCid") as String).hex.take(16), "curationReceiptCid" to receipt.value,
                "ontologyCid" to inputs.getValue("ontologyCid"), "reviewStatus" to "unreviewed")
        })
        val intake = CurationIntake(Corpus(), { runners }, { null }, this,
            settled = { settled.send(Unit) },
            retainedSource = { _, _ ->
                DocumentSource(ContentId.of(sourceText.encodeToByteArray()), ContentId.of(sourceText.encodeToByteArray()),
                    sourceText, "agreement.pdf", "application/pdf", "test")
            },
            mode = { _, _ -> CurationIntake.Mode.ReviewRequired(ontology) })
        suspend fun runOffer(project: String) {
            intake.offer(project, "agreement.pdf")
            withTimeout(5_000) { settled.receive() }
        }
        try {
            runOffer("first")
            runOffer("first")
            var status = intake.status().getValue("first") as Map<*, *>
            assertEquals(1, status["queued"])
            assertEquals(1, status["awaitingReview"])
            assertEquals(1, (status["awaitingReviews"] as List<*>).size)
            sourceText = "second version"
            runOffer("first")
            ontology = ContentId.of("ontology two".encodeToByteArray())
            runOffer("first")
            runOffer("second")
            assertEquals(5, names.size)
            assertEquals(names[0], names[1], "the same review inputs must have the same identity")
            assertTrue(names[1] != names[2], "a changed source needs a distinct book identity")
            assertTrue(names[2] != names[3], "a changed ontology needs a distinct book identity")
            assertTrue(names[3] != names[4], "the same filename in a different project needs a distinct identity")
            status = intake.status().getValue("first") as Map<*, *>
            assertEquals(1, status["queued"])
            assertEquals(1, status["awaitingReview"])
            assertEquals(1, (status["awaitingReviews"] as List<*>).size)
        } finally {
            intake.drain()
        }
    }

    @Test
    fun reofferedRevisionIsProcessedAfterRunningCurationWhetherOrNotItWasForgotten(): Unit = runBlocking {
        val ontology = ContentId.of("ontology".encodeToByteArray())
        val receipt = ContentId.of("receipt".encodeToByteArray())
        for (forget in listOf(false, true)) {
            var sourceText = "first version"
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val texts = mutableListOf<String>()
            val books = mutableListOf<String>()
            val runners = mapOf(ConstellationNodes.CURATE to LcncNodeRunner { node, inputs ->
                val retained = inputs.getValue("source") as Map<*, *>
                texts.add(retained["text"] as String)
                val book = node.params.getValue("book") + "@" + ontology.hex.take(16)
                books.add(book)
                if (texts.size == 1) {
                    started.complete(Unit)
                    release.await()
                }
                mapOf("book" to book, "curationReceiptCid" to receipt.value,
                    "ontologyCid" to ontology.value, "reviewStatus" to "unreviewed")
            })
            val intake = CurationIntake(Corpus(), { runners }, { null }, this,
                retainedSource = { _, _ ->
                    val cid = ContentId.of(sourceText.encodeToByteArray())
                    DocumentSource(cid, cid, sourceText, "agreement.pdf", "application/pdf", "test")
                }, mode = { _, _ -> CurationIntake.Mode.ReviewRequired(ontology) })
            intake.offer("project", "agreement.pdf")
            try {
                withTimeout(5_000) { started.await() }
                if (forget) intake.forget("project", "agreement.pdf")
                sourceText = "intermediate version"
                intake.offer("project", "agreement.pdf")
                sourceText = "second version"
                intake.offer("project", "agreement.pdf")
                val draining = async { intake.drain() }
                yield()
                assertFalse(draining.isCompleted, "drain must wait for the deferred revision")
                release.complete(Unit)
                withTimeout(5_000) { draining.await() }
                assertEquals(listOf("first version", "second version"), texts)
                assertEquals(2, books.size)
                assertTrue(books[0] != books[1])
                val status = intake.status().getValue("project") as Map<*, *>
                assertEquals(1, status["queued"])
                assertEquals(1, status["awaitingReview"])
                assertEquals(0, status["failed"])
                assertEquals(0, status["pending"])
                val observations = status["awaitingReviews"] as List<*>
                assertEquals(1, observations.size)
                assertEquals(books[1], (observations.single() as Map<*, *>)["book"])
            } finally {
                release.complete(Unit)
                intake.drain()
            }
        }
    }

    @Test
    fun followupUsesLatestModeAndProviderAfterSuspendedReview(): Unit = runBlocking {
        val ontology = ContentId.of("ontology".encodeToByteArray())
        val receipt = ContentId.of("receipt".encodeToByteArray())
        val source = DocumentSource(ContentId.of("original".encodeToByteArray()),
            ContentId.of("text".encodeToByteArray()), "text", "agreement.pdf", "application/pdf", "test")
        var mode: CurationIntake.Mode = CurationIntake.Mode.ReviewRequired(ontology)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val calls = mutableListOf<String>()
        val runners = mapOf(
            ConstellationNodes.CURATE to LcncNodeRunner { node, inputs ->
                if ("ontologyCid" in inputs) {
                    calls.add("review")
                    started.complete(Unit)
                    release.await()
                    mapOf("book" to node.params.getValue("book") + "@" + ontology.hex.take(16),
                        "curationReceiptCid" to receipt.value, "ontologyCid" to ontology.value,
                        "reviewStatus" to "unreviewed")
                } else {
                    calls.add("legacy")
                    mapOf("book" to inputs.getValue("text"))
                }
            },
            ConstellationNodes.JOIN to LcncNodeRunner { _, _ -> calls.add("join"); emptyMap() },
        )
        val intake = CurationIntake(Corpus(), { runners }, { null }, this,
            selectedProvider = { _, _ -> DecisionProvider.JEV }, retainedSource = { _, _ -> source },
            mode = { _, _ -> mode }, score = { provider, book ->
                assertEquals(DecisionProvider.JEV, provider)
                assertEquals("text", book)
                calls.add("score")
            })
        intake.offer("project", "agreement.pdf")
        try {
            withTimeout(5_000) { started.await() }
            mode = CurationIntake.Mode.Legacy
            intake.offer("project", "agreement.pdf")
            val draining = async { intake.drain() }
            yield()
            assertFalse(draining.isCompleted)
            release.complete(Unit)
            withTimeout(5_000) { draining.await() }
            assertEquals(listOf("review", "legacy", "join", "score", "join"), calls)
            val status = intake.status().getValue("project") as Map<*, *>
            assertEquals(1, status["queued"])
            assertEquals(1, status["curated"])
            assertEquals(0, status["awaitingReview"])
            assertEquals(0, status["failed"])
            assertEquals(0, status["pending"])
            assertTrue((status["awaitingReviews"] as List<*>).isEmpty())
        } finally {
            release.complete(Unit)
            intake.drain()
        }
    }

    @Test
    fun startedLegacyReofferJoinsLatestSourceWhileQueuedDuplicatesCoalesce(): Unit = runBlocking {
        var text = "first version"
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val parsed = mutableListOf<String>()
        val joined = mutableListOf<String>()
        val runners = mapOf(
            ConstellationNodes.CURATE to LcncNodeRunner { _, inputs ->
                val book = inputs.getValue("text") as String
                parsed.add(book)
                if (parsed.size == 1) {
                    started.complete(Unit)
                    release.await()
                }
                mapOf("book" to book)
            },
            ConstellationNodes.JOIN to LcncNodeRunner { _, inputs ->
                joined.add(inputs.getValue("book") as String)
                emptyMap()
            },
        )
        val intake = CurationIntake(Corpus(), { runners }, { null }, this,
            retainedSource = { _, _ ->
                val cid = ContentId.of(text.encodeToByteArray())
                DocumentSource(cid, cid, text, "agreement.pdf", "application/pdf", "test")
            })
        intake.offer("project", "agreement.pdf")
        intake.offer("project", "agreement.pdf")
        try {
            withTimeout(5_000) { started.await() }
            text = "second version"
            intake.offer("project", "agreement.pdf")
            val draining = async { intake.drain() }
            yield()
            assertFalse(draining.isCompleted)
            release.complete(Unit)
            withTimeout(5_000) { draining.await() }
            assertEquals(listOf("first version", "second version"), parsed)
            assertEquals(parsed, joined)
            val status = intake.status().getValue("project") as Map<*, *>
            assertEquals(0, status["failed"])
            assertEquals(0, status["pending"])
        } finally {
            release.complete(Unit)
            intake.drain()
        }
    }

    @Test
    fun newAdmissionWaitsUntilIdleSettlementFinishes(): Unit = runBlocking {
        val settling = CompletableDeferred<Unit>()
        val releaseSettlement = CompletableDeferred<Unit>()
        val parsed = mutableListOf<String>()
        var settlements = 0
        val runners = mapOf(
            ConstellationNodes.CURATE to LcncNodeRunner { _, inputs ->
                val book = inputs.getValue("text") as String
                parsed.add(book)
                mapOf("book" to book)
            },
            ConstellationNodes.JOIN to LcncNodeRunner { _, _ -> emptyMap() },
        )
        val intake = CurationIntake(Corpus(), { runners }, { null }, this, settled = {
            settlements++
            if (settlements == 1) {
                settling.complete(Unit)
                releaseSettlement.await()
            }
        })
        intake.offer("project", "first.pdf")
        try {
            withTimeout(5_000) { settling.await() }
            intake.offer("project", "second.pdf")
            val draining = async { intake.drain() }
            repeat(10) { yield() }
            assertEquals(listOf("first.pdf"), parsed, "new parsing must wait for settlement")
            assertFalse(draining.isCompleted)
            releaseSettlement.complete(Unit)
            withTimeout(5_000) { draining.await() }
            assertEquals(listOf("first.pdf", "second.pdf"), parsed)
            val status = intake.status().getValue("project") as Map<*, *>
            assertEquals(2, status["curated"])
            assertEquals(0, status["failed"])
            assertEquals(0, status["pending"])
        } finally {
            releaseSettlement.complete(Unit)
            intake.drain()
        }
    }

    @Test
    fun reviewIntakeDoesNotOverwriteABookAlreadyInAConstellation(): Unit = runBlocking {
        val directory = Files.createTempDirectory("intake-review-members-").toFile()
        val nodes = ConstellationNodes(directory, ConfixBlackboard(), scope = this)
        val source = DocumentSource(ContentId.of("original".encodeToByteArray()),
            ContentId.of("text".encodeToByteArray()), "text", "agreement.pdf", "application/pdf", "test")
        val ontology = ContentId.of("ontology".encodeToByteArray())
        val receipt = ContentId.of("receipt".encodeToByteArray())
        val settled = Channel<Unit>(Channel.UNLIMITED)
        val books = mutableListOf<String>()
        val runners = mapOf(ConstellationNodes.CURATE to LcncNodeRunner { node, _ ->
            val book = node.params.getValue("book") + "@" + ontology.hex.take(16)
            books.add(book)
            mapOf("book" to book, "curationReceiptCid" to receipt.value,
                "ontologyCid" to ontology.value, "reviewStatus" to "unreviewed")
        })
        val intake = CurationIntake(Corpus(), { runners }, { nodes }, this,
            settled = { settled.send(Unit) }, retainedSource = { _, _ -> source },
            mode = { _, _ -> CurationIntake.Mode.ReviewRequired(ontology) })
        try {
            intake.offer("project", "agreement.pdf")
            withTimeout(5_000) { settled.receive() }
            assertEquals(1, books.size)
            File(nodes.root, "legacy.members").writeText("${books.single()}\n")
            intake.offer("project", "agreement.pdf")
            withTimeout(5_000) { settled.receive() }
            assertEquals(1, books.size, "a member must be detected before the parser overwrites its book")
            val status = intake.status().getValue("project") as Map<*, *>
            assertEquals(1, status["failed"])
            assertEquals(0, status["pending"])
            assertTrue((status["note"] as String).contains("overwrite"))
        } finally {
            intake.drain()
            nodes.drain()
            directory.deleteRecursively()
        }
    }
    @Test
    fun blockedPolicyFailsClosedWithoutParsingJoiningOrScoring(): Unit = runBlocking {
        val calls = mutableListOf<String>()
        val runners = mapOf(
            ConstellationNodes.CURATE to LcncNodeRunner { _, _ -> calls.add("curate"); emptyMap() },
            ConstellationNodes.JOIN to LcncNodeRunner { _, _ -> calls.add("join"); emptyMap() },
            ConstellationNodes.BRIEF to LcncNodeRunner { _, _ -> calls.add("brief"); emptyMap() },
        )
        val intake = CurationIntake(Corpus(), { runners }, { null }, this,
            selectedProvider = { _, _ -> DecisionProvider.JEV },
            grab = { calls.add("grab") },
            mode = { _, _ -> CurationIntake.Mode.Blocked("unreadable policy") },
            score = { _, _ -> calls.add("score") })
        intake.offer("project", "agreement.pdf")
        intake.drain()
        assertTrue(calls.isEmpty())
        val status = intake.status().getValue("project") as Map<*, *>
        assertEquals(1, status["failed"])
        assertEquals(0, status["curated"])
        assertEquals(0, status["awaitingReview"])
        assertEquals(0, status["pending"])
    }

}
