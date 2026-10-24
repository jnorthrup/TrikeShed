package borg.trikeshed.agent.modelmux

import borg.trikeshed.cas.LineCas
import borg.trikeshed.cas.LineCasSourceRanges
import borg.trikeshed.job.CasStore
import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.*
import java.lang.System
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.system.measureNanoTime
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Opt-in measurement, not a pass/fail performance gate: it builds a synthetic corpus, runs the real acquire and project
 * paths over it, prints what it measured, and fails only if the answer is WRONG (a planted needle is missing).
 *
 *   TRIKESHED_TEST_MEASURE=1 ./gradlew --offline :jvmTest -PkotlinJsApps= --rerun \
 *       --tests 'borg.trikeshed.agent.modelmux.SharedContextCostMeasurementTest' -i
 *
 * Skipped (not passed) without the variable. The numbers describe one machine and one synthetic distribution; they
 * support no claim about another corpus.
 */
class SharedContextCostMeasurementTest {
    private val scope = "measure/a"

    /** A deterministic Zipf-like vocabulary so term popularity is realistic: a few words everywhere, a long tail of rare ones. */
    private fun corpus(documents: Int, linesPerDocument: Int, needles: Map<Int, String>): List<Pair<String, String>> {
        val vocabulary = Array(5_000) { "w" + (it * 7919L % 100_003L).toString(36) }
        var state = 0x2545F4914F6CDD1DL
        fun next(): Int { state = state xor (state shl 13); state = state xor (state ushr 7); state = state xor (state shl 17); return (state ushr 33).toInt() }
        return (0 until documents).map { d ->
            val text = StringBuilder(linesPerDocument * 64)
            for (l in 0 until linesPerDocument) {
                val ordinal = d * linesPerDocument + l
                needles[ordinal]?.let { text.append(it).append('\n'); return@let }
                    ?: run {
                        text.append("Line ").append(ordinal).append(':')
                        repeat(8) {
                            // Squaring a uniform draw skews picks toward the head of the vocabulary.
                            val u = (next() and 0x7fffffff).toDouble() / Int.MAX_VALUE
                            text.append(' ').append(vocabulary[(u * u * vocabulary.size).toInt()])
                        }
                        text.append('\n')
                    }
            }
            "doc-" + d.toString().padStart(4, '0') + ".md" to text.toString()
        }
    }

    private fun usedHeapMiB(): Long {
        repeat(3) { System.gc() }
        val r = Runtime.getRuntime()
        return (r.totalMemory() - r.freeMemory()) / 1_048_576
    }

    @Test
    fun measureAcquireAndProjectOverAGrowingCorpus() {
        assumeTrue(System.getenv("TRIKESHED_TEST_MEASURE") == "1", "set TRIKESHED_TEST_MEASURE=1 to run the measurement")
        val needleTerms = listOf("zyzzyvalimit", "quokkacheckpoint", "narwhalquota", "axolotlreplay", "pangolinescrow")
        println("MEASURE machine: ${Runtime.getRuntime().availableProcessors()} cpus, max heap ${Runtime.getRuntime().maxMemory() / 1_048_576} MiB, ${System.getProperty("java.vm.name")} ${System.getProperty("java.version")}")
        println("MEASURE lines | docs | bytes | build ms | acquire ms (cold) | acquire ms (warm) | plain project ms | project ms (cold, builds index) | project ms (new needle question) | project ms (every line matches) | retained MiB | needles found")
        for (lines in listOf(10_000, 50_000, 200_000)) {
            val docs = lines / 200
            val needles = needleTerms.mapIndexed { i, term -> (lines / 6) * (i + 1) to "The $term is set to ${i + 3} and must be kept." }.toMap()
            val data = corpus(docs, 200, needles)
            val totalBytes = data.sumOf { it.second.encodeToByteArray().size.toLong() }

            lateinit var revision: Revision
            val buildMs = measureNanoTime { revision = revision(scope, *data.toTypedArray()) } / 1_000_000
            val cas = revision.intoCas(CasStore.inMemory())
            val context = SharedContext(cas, null, ContextReadPolicy(allowedScopes = setOf(scope), maxBlocks = 4_096, maxTotalBytes = 512L * 1_048_576))
            val heapBefore = usedHeapMiB()
            lateinit var snapshot: ContextSnapshot
            val acquireMs = measureNanoTime { snapshot = runBlocking { context.acquire(revision.cid, scope) } } / 1_000_000
            val warmAcquireMs = measureNanoTime { runBlocking { context.acquire(revision.cid, scope) } } / 1_000_000
            val question = needleTerms.joinToString(" ")
            lateinit var plain: Projection
            val plainMs = measureNanoTime { plain = ReferenceProjector.project(snapshot, question) } / 1_000_000
            lateinit var projection: Projection
            val coldMs = measureNanoTime { projection = BreadcrumbProjector.project(snapshot, question, facets = context.facets) } / 1_000_000
            val newMs = measureNanoTime { BreadcrumbProjector.project(snapshot, "zyzzyvalimit quokkacheckpoint", facets = context.facets) } / 1_000_000
            val broadMs = measureNanoTime { BreadcrumbProjector.project(snapshot, "line", facets = context.facets) } / 1_000_000
            val retained = usedHeapMiB() - heapBefore
            val rendered = projection.render()
            val found = needleTerms.count { it in rendered }
            println("MEASURE ${"%7d".format(lines)} | ${"%4d".format(docs)} | ${"%9d".format(totalBytes)} | ${"%8d".format(buildMs)} | ${"%17d".format(acquireMs)} | ${"%17d".format(warmAcquireMs)} | ${"%16d".format(plainMs)} | ${"%31d".format(coldMs)} | ${"%32d".format(newMs)} | ${"%31d".format(broadMs)} | ${"%12d".format(retained)} | $found/${needleTerms.size}")
            assertTrue(found == needleTerms.size, "at $lines lines only $found of ${needleTerms.size} planted needles were retrieved: $rendered")
            assertTrue(rendered.length <= ProjectionBudget().maxChars, "the budget held at $lines lines")
            assertTrue(plain.render() == rendered, "at $lines lines the indexed projector and the plain one disagree")
            println("MEASURE   facets: ${context.facets.counters()}")
            if (lines == 200_000) breakdown(snapshot)
        }
    }

    /**
     * Where the per-question time goes at the largest size: the same calls acquire and project make, timed apart. Each is
     * run twice so JIT warm-up shows up as the difference between the first and second figure.
     */
    private fun breakdown(snapshot: ContextSnapshot) {
        fun twice(label: String, block: () -> Unit) {
            val first = measureNanoTime(block) / 1_000_000
            val second = measureNanoTime(block) / 1_000_000
            println("MEASURE   $label: $first ms first, $second ms second")
        }
        val texts = (0 until snapshot.documents.size).map { snapshot.documents[it].text }
        val bytes = texts.map { it.encodeToByteArray() }
        val spans = texts.map { LineCasSourceRanges.of(it) }
        val trimmed = texts.indices.flatMap { d -> (0 until spans[d].size).map { i -> texts[d].substring(spans[d][i].text.start, spans[d][i].text.end) } }
        val distinct = trimmed.distinct()
        println("MEASURE breakdown at ${trimmed.size} lines (${distinct.size} distinct), ${bytes.sumOf { it.size }} bytes:")
        twice("acquire: exact UTF-8 check") { for (b in bytes) ManifestDocument.isExactText(b) }
        twice("acquire: spine id (LineCas.spine + spineCid)") { for (b in bytes) ManifestDocument.spineOf(b) }
        twice("project: line ranges (LineCasSourceRanges.of)") { for (t in texts) LineCasSourceRanges.of(t) }
        twice("project: sha-256 content id of every line") { for (t in trimmed) LineCas.contentOf(t) }
        twice("project: tokenize every distinct line") { for (t in distinct) BreadcrumbProjector.tokens(t) }
    }
}
