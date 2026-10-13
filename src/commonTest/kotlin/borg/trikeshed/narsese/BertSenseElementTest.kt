package borg.trikeshed.narsese

import borg.trikeshed.lib.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class BertSenseElementTest {
    class Encoder : BertEncoder {
        override val model = "fixture-encoder"
        var calls = 0
        var closed = false
        override suspend fun encode(tokens: Series<BertToken>): Series<BertVector> {
            calls++
            return tokens α { BertVector(it, BertSpan.Exact, floatArrayOf(1f, 0f)) }
        }
        override suspend fun drain() { closed = true }
    }
    private val query = BertSenseQuery("query", BertToken("bank", "The bank holds the account."), s_["Organization"])
    private val labels = 7 j { i: Int -> BertSenseLabel("source-$i", "Organization", BertToken("bank", "Training $i")) }

    @Test fun noKeyPathUsesOnlyTheLocalEncoderAndRetainsSources() = runTest {
        val encoder = Encoder()
        val lane = BertSenseElement(this, { encoder })
        val first = lane.read(s_[query], labels)
        assertEquals(BertSenseStatus.Supported, first.results[0].status)
        assertEquals(7, first.results[0].neighbors.size)
        assertEquals("fixture-encoder", first.model)
        val calls = encoder.calls
        assertEquals(BertSenseStatus.Supported, lane.read(s_[query], labels).results[0].status)
        assertEquals(calls, encoder.calls, "unchanged target vectors are reused")
        lane.drain()
        assertTrue(encoder.closed)
        assertFailsWith<IllegalStateException> { lane.read(s_[query], labels) }
    }
    @Test fun missingEvidenceDoesNotStartAnEncoderOrInventAResult() = runTest {
        var started = false
        val lane = BertSenseElement(this, { started = true; Encoder() })
        assertEquals(BertSenseStatus.InsufficientEvidence, lane.read(s_[query], emptySeriesOf()).results[0].status)
        assertFalse(started)
        lane.drain()
    }
    @Test fun unavailableRuntimeIsExplicitWithoutStoppingOtherCuration() = runTest {
        val lane = BertSenseElement(this, { error("local model missing") })
        val result = lane.read(s_[query], labels)
        assertEquals(BertSenseStatus.Unavailable, result.results[0].status)
        assertEquals("local model missing", result.error)
        assertNull(result.results[0].sense)
        lane.drain()
    }
    @Test fun drainFinishesAdmittedInferenceBeforeClosingTheEncoder() = runTest {
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        var closed = false
        val encoder = object : BertEncoder {
            override val model = "delayed"
            override suspend fun encode(tokens: Series<BertToken>): Series<BertVector> {
                entered.complete(Unit); finish.await()
                return tokens α { BertVector(it, BertSpan.Exact, floatArrayOf(1f)) }
            }
            override suspend fun drain() { closed = true }
        }
        val lane = BertSenseElement(this, { encoder })
        val read = async { lane.read(s_[query], labels) }
        entered.await()
        val drain = async { lane.drain() }
        yield()
        assertFalse(drain.isCompleted); assertFalse(closed)
        finish.complete(Unit)
        assertEquals(BertSenseStatus.Supported, read.await().results[0].status)
        drain.await(); assertTrue(closed)
    }
}
