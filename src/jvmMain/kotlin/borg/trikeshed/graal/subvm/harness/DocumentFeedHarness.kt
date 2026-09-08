package borg.trikeshed.graal.subvm.harness

import borg.trikeshed.couch.isam.DurableAppendLog
import borg.trikeshed.couch.isam.WalFrame
import borg.trikeshed.graal.ConfixBlackboard
import borg.trikeshed.graal.subvm.CamelRuntime
import borg.trikeshed.graal.subvm.DocumentFeed
import borg.trikeshed.graal.subvm.GuestModules
import borg.trikeshed.graal.subvm.TikaRuntime
import borg.trikeshed.job.CasStore
import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.get
import borg.trikeshed.lib.size
import borg.trikeshed.modelmux.ModelResponse
import borg.trikeshed.modelmux.ModelUsage
import borg.trikeshed.modelmux.Prompt
import borg.trikeshed.narsese.BeliefBagElement
import borg.trikeshed.narsese.DocumentCuratorCodec
import borg.trikeshed.parse.json.JsonSupport
import borg.trikeshed.pointcut.PointcutBlackboardAdapter
import borg.trikeshed.userspace.nio.ByteBuffer
import borg.trikeshed.userspace.nio.DocumentExtent
import borg.trikeshed.userspace.nio.InMemoryVolume
import borg.trikeshed.userspace.nio.Volume
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import java.awt.Color
import java.awt.Font
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/** Real managed libraries; deterministic model and explicitly volatile IO fixtures. */
object DocumentFeedHarness {
    @JvmStatic
    fun main(args: Array<String>) = runBlocking {
        System.setProperty("java.awt.headless", "true")
        require(args.isEmpty() || args.contentEquals(arrayOf("--ocr"))) { "Supported argument: --ocr" }
        println(JsonSupport.stringify(run(CoroutineScope(currentCoroutineContext()), args.isNotEmpty())))
    }

    suspend fun run(scope: CoroutineScope, ocr: Boolean = false): Map<String, Any?> {
        for (module in arrayOf("tika", "camel", "corenlp")) check(GuestModules.isInstalled(module)) {
            "Install managed module $module with ./gradlew -p utils/subvm install${module.replaceFirstChar { it.uppercase() }}"
        }
        check(runCatching { javaClass.classLoader.loadClass("org.apache.tika.parser.AutoDetectParser") }.isFailure) {
            "Tika must not be on the application classpath"
        }
        val backing = InMemoryVolume(512, 256)
        var readCalls = 0
        var writeCalls = 0
        val volume = object : Volume by backing {
            override suspend fun read(lba: Long, count: Int): ByteBuffer {
                readCalls++
                return backing.read(lba, count)
            }
            override suspend fun write(lba: Long, data: ByteBuffer) {
                writeCalls++
                backing.write(lba, data)
            }
        }
        var nextLba = 0L
        suspend fun stage(name: String, bytes: ByteArray, mediaType: String): DocumentExtent {
            val extent = DocumentExtent(nextLba, bytes.size, name, mediaType, ContentId.of(bytes))
            volume.write(nextLba, ByteBuffer(bytes))
            volume.sync()
            nextLba += (bytes.size + volume.blockSize - 1) / volume.blockSize
            return extent
        }
        val pdf = stage("supported.pdf", pdf("Acme pays Beta."), "application/pdf")
        val long = stage("long.html", ("<html><body>" + "<p>Schedule.</p>".repeat(520) +
            "<p>Gamma pays Delta.</p></body></html>").encodeToByteArray(), "text/html")
        val conflict = stage("conflict.txt", "Acme pays Beta. Acme does not pay Beta.".encodeToByteArray(), "text/plain")
        val retry = stage("retry.txt", "Alpha pays Omega.".encodeToByteArray(), "text/plain")
        val wrong = stage("span.txt", "Theta pays Sigma.".encodeToByteArray(), "text/plain")
        val raster = if (ocr) stage("ocr.png", raster("Acme pays Beta."), "image/png") else null
        val cas = CasStore.inMemory()
        val log = MemoryLog()
        val bag = BeliefBagElement(parentJob = scope.coroutineContext[Job])
        val points = PointcutBlackboardAdapter(ConfixBlackboard())
        val model = FixtureModel()
        val receipts = ArrayList<DocumentFeed.Receipt>()
        bag.open()
        var feed: DocumentFeed? = null
        try {
            feed = DocumentFeed.create(scope, volume, cas, log, bag, points, model::invoke, "fixture")
            val supported = feed.submit(pdf).also(receipts::add)
            check(supported.record.submittedReceiptCids.size == 1) { describe(supported) }
            val duplicate = feed.submit(pdf).also(receipts::add)
            check(duplicate.record.submittedReceiptCids.size == 0 && duplicate.record.duplicateReceiptCids.size == 1)
            val complete = feed.submit(long).also(receipts::add)
            check(complete.record.source.text.length > CamelRuntime.MAX_FACT_BODY)
            check(complete.record.source.text.contains("Gamma pays Delta."))
            check(complete.record.submittedReceiptCids.size == 1) { describe(complete) }
            val disputed = feed.submit(conflict).also(receipts::add)
            check(disputed.record.submittedReceiptCids.size == 0)
            check(disputed.record.proposals.size == 2)
            check((0 until disputed.record.proposals.size).all { disputed.record.proposals[it].reasons.size > 0 })
            val refused = feed.submit(wrong).also(receipts::add)
            check(refused.record.submittedReceiptCids.size == 0 && refused.record.proposals[0].reasons.size > 0)
            val failed = feed.submit(retry).also(receipts::add)
            check(failed.record.submittedReceiptCids.size == 0 && failed.record.reasons.size > 0)
            val recovered = feed.submit(retry).also(receipts::add)
            check(recovered.record.submittedReceiptCids.size == 1) { describe(recovered) }
            val route = feed.routeId
            feed.drain()
            check(!CamelRuntime.isRunning(route) && feed.job.isCompleted)
            check(feed.observationFailureCount == 0) { feed.lastObservationFailure.orEmpty() }
            feed = null

            // Recreate the curator over the same stored frames and CAS, not its old in-memory sets.
            feed = DocumentFeed.create(scope, volume, cas, log, bag, points, model::invoke, "fixture")
            val replayed = feed.submit(pdf).also(receipts::add)
            check(replayed.record.submittedReceiptCids.size == 0 && replayed.record.duplicateReceiptCids.size == 1)
            feed.drain()
            check(feed.observationFailureCount == 0) { feed.lastObservationFailure.orEmpty() }
            feed = null

            if (raster != null) {
                feed = DocumentFeed.create(scope, volume, cas, log, bag, points, model::invoke, "fixture",
                    tikaOptions = TikaRuntime.TikaOptions(ocr = TikaRuntime.OcrOptions(
                        preprocessImages = true, requireTesseract = true)))
                val recognized = feed.submit(raster).also(receipts::add)
                check(recognized.record.source.originalCid == raster.expectedCid)
                check(recognized.record.submittedReceiptCids.size == 1) { describe(recognized) }
                check(recognized.record.source.metadata["trikeshed:source:transform"] ==
                    listOf("ffmpeg:${TikaRuntime.TIKA4ALL_FFMPEG_FILTER}"))
                feed.drain()
                check(feed.observationFailureCount == 0) { feed.lastObservationFailure.orEmpty() }
                check(!CamelRuntime.isRunning(feed.routeId) && feed.job.isCompleted)
                feed = null
            }
        } finally {
            try { feed?.drain() } finally { bag.drain() }
        }
        val expectedAttributions = if (ocr) 4 else 3
        check(bag.size == expectedAttributions) { "Expected $expectedAttributions source attributions, found ${bag.size}" }
        for ((key, signal) in bag.snapshot()) {
            val gloss = bag.glossOf(signal.angular) ?: error("Missing attribution expression")
            check(gloss.startsWith("(states ")) { "Inner assertion was admitted: $gloss" }
            check(signal.provenanceCid != null && cas.get(ContentId(signal.provenanceCid!!)) != null)
        }
        check(readCalls >= receipts.size && writeCalls == if (ocr) 6 else 5)
        check(log.flushes > 0)
        for (receipt in receipts) {
            val saved = DocumentCuratorCodec.decode(cas.get(receipt.cid) ?: error("Missing record"))
            check(saved.source.text == receipt.record.source.text)
            check(ContentId.of(saved.source.text.encodeToByteArray()) == saved.source.extractedTextCid)
            check(cas.get(saved.source.originalCid) != null)
            check(saved.observerFailures.size == 0)
            val stages = HashSet<String>()
            val landings = points.landings
            for (i in 0 until landings.size) {
                val landing = landings[i]
                if ((landing.value as? Map<*, *>)?.get("correlation") == saved.source.correlation)
                    stages.add(landing.coordinate.methodName)
            }
            check(stages.containsAll(setOf("tap", "exchange", "extraction", "input", "nlp", "model", "join", "record"))) {
                "Incomplete correlated pointcuts: $stages"
            }
            if (saved.submittedReceiptCids.size > 0) check("intake" in stages)
        }
        return mapOf("managedLibraries" to listOf("Tika", "Camel", "CoreNLP"),
            "model" to "deterministic fixture; no provider call", "receipts" to receipts.size,
            "sourceAttributions" to bag.size, "volumeReads" to readCalls, "volumeWrites" to writeCalls,
            "pointcutLandings" to points.landings.size, "duplicateAndReplay" to true,
            "conflictAndInvalidSpanRetained" to true, "modelFailureRetry" to true,
            "rasterOcrPreprocessing" to if (ocr) "verified" else "not exercised",
            "storage" to "volatile userspace volume/CAS/log fixtures; not disk durability or native io_uring",
            "recordCids" to receipts.map { it.cid.value })
    }

    private fun raster(text: String): ByteArray {
        val image = BufferedImage(1000, 160, BufferedImage.TYPE_INT_RGB)
        image.createGraphics().let { graphics ->
            try {
                graphics.color = Color.WHITE
                graphics.fillRect(0, 0, image.width, image.height)
                graphics.color = Color.BLACK
                graphics.font = Font(Font.SANS_SERIF, Font.PLAIN, 72)
                graphics.drawString(text, 25, 110)
            } finally { graphics.dispose() }
        }
        return ByteArrayOutputStream().use { output ->
            check(ImageIO.write(image, "png", output))
            output.toByteArray()
        }
    }

    private fun describe(receipt: DocumentFeed.Receipt): String = JsonSupport.stringify(mapOf(
        "name" to receipt.record.source.name, "reasons" to List(receipt.record.reasons.size) { receipt.record.reasons[it] },
        "proposals" to List(receipt.record.proposals.size) { i -> receipt.record.proposals[i].let {
            mapOf("raw" to it.raw, "reasons" to List(it.reasons.size) { r -> it.reasons[r] }) } }))

    private class FixtureModel {
        private var failedRetry = false
        suspend fun invoke(prompt: Prompt): ModelResponse {
            val source = JsonSupport.parse(prompt.messages[1].content) as Map<*, *>
            val text = source["text"] as String
            val name = source["name"] as String
            if (name == "retry.txt" && !failedRetry) {
                failedRetry = true
                error("fixture transient model failure")
            }
            val quote = when (name) {
                "long.html" -> "Gamma pays Delta."
                "retry.txt" -> "Alpha pays Omega."
                "span.txt" -> "Theta pays Sigma."
                else -> "Acme pays Beta."
            }
            val terms = quote.removeSuffix(".").split(' ')
            fun proposal(q: String, polarity: Boolean, wrong: Boolean = false): Map<String, Any?> {
                val begin = text.indexOf(q)
                check(begin >= 0) { "Full fixture text was not delivered" }
                return mapOf("subject" to terms[0], "predicate" to "pay", "object" to terms[2],
                    "confidence" to 0.7, "quote" to q, "begin" to begin + if (wrong) 1 else 0,
                    "end" to begin + q.length, "polarity" to polarity, "modality" to "asserted")
            }
            val proposals = if (name == "conflict.txt")
                listOf(proposal(quote, true), proposal("Acme does not pay Beta.", false))
            else listOf(proposal(quote, true, name == "span.txt"))
            return ModelResponse(JsonSupport.stringify(mapOf("format" to "TRIPLET_JSON", "triplets" to proposals)),
                ModelUsage(-1, -1, -1), "fixture", "fixture")
        }
    }

    /** Framed replay fixture, explicitly not durable beyond this process. */
    private class MemoryLog : DurableAppendLog {
        private val frames = ArrayList<Pair<Long, ByteArray>>()
        var flushes = 0
        override fun append(sequence: Long, payload: ByteArray): Long {
            frames.add(sequence to WalFrame.encode(sequence, payload))
            return sequence
        }
        override suspend fun replay(onFrame: suspend (Long, ByteArray) -> Unit): Long {
            var last = 0L
            for ((sequence, frame) in frames) {
                if (!WalFrame.validate(frame)) break
                onFrame(sequence, frame.copyOfRange(WalFrame.HEADER_SIZE, frame.size - 4))
                last = sequence
            }
            return last
        }
        override fun flush() { flushes++ }
        override fun injectCorruptionAfter(sequence: Long) {
            frames.firstOrNull { it.first > sequence }?.second?.let { it[0] = 0 }
        }
    }

    private fun pdf(text: String): ByteArray {
        val content = "BT /F1 12 Tf 72 720 Td (${text.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)")}) Tj ET\n"
        val objects = arrayOf("<< /Type /Catalog /Pages 2 0 R >>", "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>",
            "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>", "<< /Length ${content.length} >>\nstream\n${content}endstream")
        val out = StringBuilder("%PDF-1.4\n")
        val offsets = IntArray(objects.size)
        for (i in objects.indices) {
            offsets[i] = out.length
            out.append("${i + 1} 0 obj\n${objects[i]}\nendobj\n")
        }
        val xref = out.length
        out.append("xref\n0 6\n0000000000 65535 f \n")
        for (offset in offsets) out.append(offset.toString().padStart(10, '0')).append(" 00000 n \n")
        out.append("trailer\n<< /Size 6 /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return out.toString().encodeToByteArray()
    }
}
