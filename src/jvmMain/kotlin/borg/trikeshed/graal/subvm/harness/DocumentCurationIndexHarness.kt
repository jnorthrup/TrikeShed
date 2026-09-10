package borg.trikeshed.graal.subvm.harness

import borg.trikeshed.couch.isam.DurableAppendLog
import borg.trikeshed.couch.isam.WalFrame
import borg.trikeshed.graal.ConfixBlackboard
import borg.trikeshed.graal.subvm.CamelRuntime
import borg.trikeshed.graal.subvm.DocumentFeed
import borg.trikeshed.graal.subvm.GuestModules
import borg.trikeshed.job.CasStore
import borg.trikeshed.job.ContentId
import borg.trikeshed.lcnc.DocumentCurationLegos
import borg.trikeshed.lcnc.LcncNode
import borg.trikeshed.lcnc.LcncNodeRunner
import borg.trikeshed.lcnc.LcncProgram
import borg.trikeshed.lcnc.LcncRunner
import borg.trikeshed.lcnc.LcncSheetNodes
import borg.trikeshed.lcnc.LcncWire
import borg.trikeshed.lib.get
import borg.trikeshed.lib.size
import borg.trikeshed.lib.toSeries
import borg.trikeshed.lib.view
import borg.trikeshed.modelmux.ModelResponse
import borg.trikeshed.modelmux.ModelUsage
import borg.trikeshed.modelmux.Prompt
import borg.trikeshed.narsese.BeliefBagElement
import borg.trikeshed.narsese.DocumentCurationIndexK
import borg.trikeshed.narsese.DocumentCurationRecord
import borg.trikeshed.narsese.DocumentCuratorCodec
import borg.trikeshed.narsese.DocumentNlpStatus
import borg.trikeshed.narsese.curationIndex
import borg.trikeshed.narsese.facet
import borg.trikeshed.parse.json.JsonSupport
import borg.trikeshed.pointcut.PointcutBlackboardAdapter
import borg.trikeshed.userspace.nio.ByteBuffer
import borg.trikeshed.userspace.nio.DocumentExtent
import borg.trikeshed.userspace.nio.InMemoryVolume
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import java.io.File

/** Real managed NLP via the registered LCNC procedure; model and storage are explicit fixtures. */
object DocumentCurationIndexHarness {
    @JvmStatic
    fun main(args: Array<String>) = runBlocking {
        require(args.size == 2) { "Arguments: repository-root subvm-root-without-corenlp" }
        val repository = File(args[0]).canonicalFile
        val sourceFile = File(repository, "PRELOAD.md")
        // Host file IO here identifies a verification input. Document ingestion below uses Volume.
        val fileBytes = sourceFile.readBytes()
        val fileText = fileBytes.decodeToString()
        val passage = "Bounded\n  channels carry work through stages and return results or failures."
        val begin = fileText.indexOf(passage)
        check(begin >= 0) { "Identified PRELOAD passage has changed; select a new real source explicitly" }
        val provenance = mapOf("path" to "PRELOAD.md", "fileCid" to ContentId.of(fileBytes).value,
            "begin" to begin, "end" to begin + passage.length, "coordinates" to "UTF-16, end exclusive",
            "line" to fileText.substring(0, begin).count { it == '\n' } + 1)
        println(JsonSupport.stringify(run(CoroutineScope(currentCoroutineContext()), passage, provenance, args[1])))
    }

    suspend fun run(scope: CoroutineScope, passage: String, provenance: Map<String, Any?>, absentRoot: String): Map<String, Any?> {
        for (module in arrayOf("tika", "camel", "corenlp")) check(GuestModules.isInstalled(module)) { "Missing managed $module" }
        for (name in arrayOf("edu.stanford.nlp.pipeline.StanfordCoreNLP", "org.apache.tika.parser.AutoDetectParser",
            "org.apache.camel.CamelContext", "org.springframework.context.ApplicationContext")) {
            check(runCatching { javaClass.classLoader.loadClass(name) }.isFailure) { "$name leaked onto host classpath" }
        }
        val volume = InMemoryVolume(512, 128)
        val cas = CasStore.inMemory()
        val log = MemoryLog()
        val bag = BeliefBagElement(parentJob = scope.coroutineContext[Job])
        val points = PointcutBlackboardAdapter(ConfixBlackboard())
        val model = ModelFixture()
        val runners = mutableMapOf<String, LcncNodeRunner>("sheet.count" to LcncSheetNodes.countNode())
        val program = program()
        val runner = LcncRunner(runners)
        var lba = 0L
        suspend fun submit(name: String, text: String): Pair<DocumentCurationRecord, Map<String, Any?>> {
            val bytes = text.encodeToByteArray()
            val extent = DocumentExtent(lba, bytes.size, name, "text/plain", ContentId.of(bytes))
            volume.write(lba, ByteBuffer(bytes))
            volume.sync()
            lba += (bytes.size + volume.blockSize - 1) / volume.blockSize
            val result = runner.runProcedure(program, mapOf("extent" to mapOf(
                "lba" to extent.lba, "byteLength" to extent.byteLength, "name" to extent.name,
                "mediaType" to extent.mediaType, "expectedCid" to extent.expectedCid?.value)))
            val cid = ContentId(result.returns["receiptCid"] as? String ?: error("Procedure did not return a receipt"))
            val record = DocumentCuratorCodec.decode(cas.get(cid) ?: error("Missing retained receipt"))
            check(record.source.originalCid == extent.expectedCid)
            check(result.returns["nlpStatus"] == record.curationIndex().facet(DocumentCurationIndexK.NlpStatus).name)
            check(result.returns["sentenceCount"] == (record.nlp?.sentences?.size ?: 0))
            return record to result.returns
        }
        bag.open()
        var feed: DocumentFeed? = null
        try {
            feed = DocumentCurationLegos.create(scope, volume, cas, log, bag, points, model::invoke, "fixture", runners)
            val (real, realOutput) = submit("PRELOAD.md:identified-passage", passage)
            check(real.nlp != null && real.nlp.sentences.size == 1)
            val index = real.curationIndex()
            check(index.facet(DocumentCurationIndexK.NlpStatus) == DocumentNlpStatus.AVAILABLE)
            val tokens = index.facet(DocumentCurationIndexK.Tokens)(0)
            check(tokens.view.any { it.word == "failures" })
            val (changed, changedOutput) = submit("fixture:source-edit", passage.replace("failures", "errors"))
            val changedTokens = changed.curationIndex().facet(DocumentCurationIndexK.Tokens)(0)
            check(changedTokens.view.any { it.word == "errors" } && changedTokens.view.none { it.word == "failures" })
            check(real.source.originalCid != changed.source.originalCid && real.source.extractedTextCid != changed.source.extractedTextCid)
            check(realOutput["sheets"] != changedOutput["sheets"]) { "Source edit failed to reach cursor sheets" }

            val fixtures = listOf(
                Fixture("positive", "Acme pays Beta.", true, "asserted"),
                Fixture("negation", "Acme does not pay Beta.", false, "asserted"),
                Fixture("condition", "If Gamma pays Delta, Acme pays Beta.", true, "conditional"),
                Fixture("modality", "Acme may pay Beta.", true, "possible"),
                Fixture("attribution", "Alice says Acme pays Beta.", true, "attributed"),
                Fixture("ambiguity", "Acme pays Beta or Gamma.", true, "alternative"),
            )
            val qualification = ArrayList<Map<String, Any?>>()
            for (fixture in fixtures) {
                model.fixture = fixture
                val (record, output) = submit("fixture:${fixture.name}", fixture.text)
                check(record.proposals.size == 1) { "Qualification fixture must attempt a proposal" }
                check(record.proposals[0].polarity == fixture.polarity && record.proposals[0].modality == fixture.modality)
                if (fixture.name == "positive") check(record.submittedReceiptCids.size == 1)
                else check(record.submittedReceiptCids.size == 0 && record.proposals[0].reasons.size > 0)
                val unconditional = if (fixture.name != "positive") {
                    model.fixture = fixture.copy(polarity = true, modality = "asserted")
                    val (proposal, _) = submit("fixture:${fixture.name}:unconditional-proposal", fixture.text)
                    check(proposal.proposals.size == 1 && proposal.submittedReceiptCids.size == 0)
                    check(proposal.proposals[0].reasons.view.any { it == "unsupported clause structure" })
                    mapOf("submitted" to 0, "reasons" to proposal.proposals[0].reasons.view.toList())
                } else null
                qualification.add(mapOf("fixture" to fixture.name, "source" to record.source.text,
                    "nlp" to nlp(record), "proposals" to record.proposals.view.map { DocumentCuratorCodec.proposal(it) },
                    "submitted" to record.submittedReceiptCids.size, "nlpStatus" to output["nlpStatus"],
                    "unconditionalProposalRejected" to unconditional))
            }
            model.fixture = null
            val priorModelCalls = model.calls
            val originalRoot = System.getProperty(GuestModules.HOME_PROPERTY)
            val unavailable = try {
                System.setProperty(GuestModules.HOME_PROPERTY, absentRoot)
                check(!GuestModules.isInstalled("corenlp"))
                submit("fixture:nlp-unavailable", "Acme pays Beta.")
            } finally {
                if (originalRoot == null) System.clearProperty(GuestModules.HOME_PROPERTY)
                else System.setProperty(GuestModules.HOME_PROPERTY, originalRoot)
            }
            check(model.calls == priorModelCalls) { "Model ran without prerequisite NLP" }
            check(unavailable.first.nlp == null && unavailable.first.model == null)
            check(unavailable.first.submittedReceiptCids.size == 0 && unavailable.first.proposals.size == 0)
            check(unavailable.second["nlpStatus"] == "UNAVAILABLE")
            check(unavailable.first.reasons.view.any { it.contains("CoreNLP module is not installed") })
            val (restored, _) = submit("fixture:nlp-restored", "Acme pays Beta.")
            check(restored.nlp != null && model.calls == priorModelCalls + 1)

            val routeId = feed.routeId
            feed.drain()
            check(feed.job.isCompleted && !CamelRuntime.isRunning(routeId))
            check(feed.observationFailureCount == 0)
            feed = null
            val stages = points.landings.view.mapNotNull { landing ->
                if ((landing.value as? Map<*, *>)?.get("correlation") == real.source.correlation) landing.coordinate.methodName else null
            }
            check(stages.indexOf("nlp") >= 0 && stages.indexOf("nlp") < stages.indexOf("model"))
            return mapOf(
                "verification" to "current-source overlay; real managed Tika/Camel/CoreNLP; registered LCNC procedure",
                "callPath" to "LcncRunner.runProcedure -> document.curate -> DocumentFeed.submit -> DocumentInputElement -> Camel -> Tika -> DocumentCuratorElement -> CoreNlpRuntime -> curationIndex -> model -> record.curationIndex -> Cursor -> sheetSeed",
                "limitations" to listOf("deterministic model fixture, no live provider", "volatile userspace Volume/CAS/log; no durability or native io_uring claim",
                    "narrow positive unmodified SVO admission; qualification fixtures retained pending", "explicit factory registration; daemon has no conforming storage binding yet"),
                "source" to provenance, "recordSource" to DocumentCuratorCodec.source(real.source),
                "curation" to mapOf("reasons" to real.reasons.view.toList(),
                    "proposals" to real.proposals.view.map { DocumentCuratorCodec.proposal(it) },
                    "submitted" to real.submittedReceiptCids.size),
                "nlp" to nlp(real), "cursorSheets" to realOutput["sheets"],
                "sheetCount" to realOutput["sentenceCount"],
                "modelInput" to model.realInput, "pointcutStages" to stages,
                "sourceEdit" to mapOf("text" to changed.source.text, "originalCid" to changed.source.originalCid.value,
                    "extractedTextCid" to changed.source.extractedTextCid.value,
                    "words" to changedTokens.view.map { it.word }, "cursorChanged" to true),
                "qualificationFixtures" to qualification,
                "missingNlp" to mapOf("nlpStatus" to unavailable.second["nlpStatus"], "modelCalls" to 0,
                    "proposals" to unavailable.first.proposals.size, "submitted" to unavailable.first.submittedReceiptCids.size,
                    "reasons" to unavailable.first.reasons.view.toList(), "sheets" to unavailable.second["sheets"]),
                "recovery" to true, "modelCalls" to model.calls, "drained" to true,
            )
        } finally {
            try { feed?.drain() } finally { bag.drain() }
        }
    }

    private fun program(): LcncProgram {
        val outputs = listOf("receiptCid", "record", "nlpStatus", "sheets")
        return LcncProgram("document-curation-index-verification", (listOf(
            LcncNode("input", "scope.in", mapOf("name" to "extent")),
            LcncNode("curate", DocumentCurationLegos.CURATE),
            LcncNode("count", "sheet.count"),
            LcncNode("out-count", "scope.out", mapOf("name" to "sentenceCount"))) + outputs.map {
                LcncNode("out-$it", "scope.out", mapOf("name" to it))
            }).toSeries(), (listOf(LcncWire("input", "value", "curate", "extent"),
                LcncWire("curate", "sheet", "count", "sheet"),
                LcncWire("count", "count", "out-count", "value")) + outputs.map {
                LcncWire("curate", it, "out-$it", "value")
            }).toSeries())
    }

    private fun nlp(record: DocumentCurationRecord): Any? =
        DocumentCuratorCodec.record(record)["nlp"]

    private data class Fixture(val name: String, val text: String, val polarity: Boolean, val modality: String)

    private class ModelFixture {
        var fixture: Fixture? = null
        var calls = 0
        var realInput: Map<*, *>? = null
        suspend fun invoke(prompt: Prompt): ModelResponse {
            val input = JsonSupport.parse(prompt.messages[1].content) as Map<*, *>
            val linguistic = input["linguistics"] as? Map<*, *> ?: error("Model did not receive linguistic index")
            check(linguistic["status"] == "AVAILABLE")
            val sentences = linguistic["sentences"] as? List<*> ?: error("Missing model sentence input")
            check(sentences.isNotEmpty())
            for (sentence in sentences) {
                val s = sentence as Map<*, *>
                check((s["tokens"] as List<*>).isNotEmpty() && (s["dependencies"] as List<*>).isNotEmpty())
            }
            calls++
            if (input["name"] == "PRELOAD.md:identified-passage") realInput = input
            val text = input["text"] as String
            val triplets = fixture?.let { f ->
                val begin = text.indexOf(f.text)
                check(begin >= 0)
                listOf(mapOf("subject" to "Acme", "predicate" to "pay", "object" to "Beta", "confidence" to 0.73,
                    "quote" to f.text, "begin" to begin, "end" to begin + f.text.length,
                    "polarity" to f.polarity, "modality" to f.modality))
            } ?: emptyList()
            return ModelResponse(JsonSupport.stringify(mapOf("format" to "TRIPLET_JSON", "triplets" to triplets)),
                ModelUsage(-1, -1, -1), "fixture", "fixture")
        }
    }

    private class MemoryLog : DurableAppendLog {
        private val frames = ArrayList<Pair<Long, ByteArray>>()
        override fun append(sequence: Long, payload: ByteArray): Long {
            frames.add(sequence to WalFrame.encode(sequence, payload))
            return sequence
        }
        override suspend fun replay(onFrame: suspend (Long, ByteArray) -> Unit): Long {
            var last = 0L
            for ((sequence, frame) in frames) {
                check(WalFrame.validate(frame))
                onFrame(sequence, frame.copyOfRange(WalFrame.HEADER_SIZE, frame.size - 4))
                last = sequence
            }
            return last
        }
        override fun flush() = Unit
        override fun injectCorruptionAfter(sequence: Long) = error("Unused corruption operation in volatile fixture")
    }
}
