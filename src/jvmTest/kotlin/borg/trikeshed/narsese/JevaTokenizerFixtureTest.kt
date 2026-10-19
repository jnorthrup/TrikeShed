package borg.trikeshed.narsese

import borg.trikeshed.lib.*
import borg.trikeshed.parse.*
import kotlin.test.*

class JevaTokenizerFixtureTest {
    /** Original token IDs and merge ranks, reduced from the real saved Jeva-4B tokenizer, not a toy vocabulary. */
    @Test fun checkpointTokenizerParityAndLengthBoundary() {
        val fixture = reifyMap(requireNotNull(javaClass.getResourceAsStream("/jeva/tokenizer.json"))
            .bufferedReader().use { it.readText() })
        val tokenizer = JevaTokenizer.parse(jsonOf(fixture.getValue("tokenizer")))
        val cases = fixture.getValue("cases") as List<*>
        for (raw in cases) {
            val row = raw as Map<*, *>
            val text = row["text"] as String
            val expected = (row["ids"] as List<*>).map { (it as Number).toInt() }
            assertEquals(expected, tokenizer.encode(text).toList(), text)
            if (expected.isNotEmpty()) {
                assertEquals(expected, tokenizer.encode(text, maxLength = expected.size).toList())
                if (expected.size > 1) assertFailsWith<IllegalArgumentException>(text) {
                    tokenizer.encode(text, maxLength = expected.size - 1)
                }
            }
        }
        assertEquals(248058, tokenizer.encode("<tool_call>")[0], "Non-special added tokens still match verbatim")
        assertEquals(248044, tokenizer.encode("<|endoftext|>")[0])
    }
}
