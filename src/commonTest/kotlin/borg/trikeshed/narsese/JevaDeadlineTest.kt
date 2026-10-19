package borg.trikeshed.narsese

import borg.trikeshed.lib.*
import kotlin.test.*
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.ExperimentalTime
import kotlin.time.TestTimeSource

@OptIn(ExperimentalTime::class)
class JevaDeadlineTest {
    @Test fun expiryIsAnOrdinaryFailureAndTheNextDecisionGetsItsOwnBudget() {
        val clock = TestTimeSource()
        val first = JevaDeadline(10, "decision 'first'", clock)
        first.location = "decision 'first' state layer 2 token 3"
        clock += 9.milliseconds
        first.check()
        clock += 1.milliseconds
        val failure = assertFailsWith<JevaDeadline.Expired> { first.check() }
        assertEquals(10L, failure.timeoutMs)
        assertEquals(10L, failure.elapsedMs)
        assertContains(failure.message.orEmpty(), "state layer 2 token 3")
        val next = JevaDeadline(10, "decision 'second'", clock)
        next.check()
        clock += 9.milliseconds
        next.check()
    }

    @Test fun backboneAndHeadStopInsideOneLongMatrixRow() {
        for (head in listOf(false, true)) {
            val clock = TestTimeSource()
            var reads = 0
            val width = 16_384
            val weight: MetaSeries<Tensor, Float> = shapeOf(1, width) j { _: Tensor ->
                reads++; clock += 1.milliseconds; 1f
            }
            val input = FloatArray(width) { 1f }
            val deadline = JevaDeadline(5, "matrix", clock)
            assertFailsWith<JevaDeadline.Expired> {
                if (head) JevaLinear(weight)(input, deadline::check)
                else JevaBackbone(JevaBackboneConfig(), { error("No checkpoint needed for a matrix") })
                    .linear(weight, input, check = deadline::check)
            }
            assertTrue(reads in 1..4096, "Expiry must be checked inside a row; observed $reads reads")
            val next = JevaDeadline(5, "next matrix", clock)
            val small = JevaLinear(tensorOf(shapeOf(1, 2), floatArrayOf(2f, 3f)))
            assertContentEquals(floatArrayOf(5f), small(floatArrayOf(1f, 1f), next::check))
        }
    }

    @Test fun bpeCanExpireBeforeAWordHasFinishedMerging() {
        val clock = TestTimeSource()
        val deadline = JevaDeadline(100, "tokenization", clock)
        val tokenizer = JevaTokenizer(mapOf("a" to 0, "aa" to 1), s_["a" j "a"])
        var checks = 0
        assertFailsWith<JevaDeadline.Expired> {
            tokenizer.encode("a".repeat(20_000), 20_000) {
                checks++; clock += 1.milliseconds; deadline.check()
            }
        }
        assertEquals(100, checks)
        assertContentEquals(intArrayOf(1, 1), tokenizer.encode("aaaa").view.toList().toIntArray())
    }
}
