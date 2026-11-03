package borg.trikeshed.narsese

import borg.trikeshed.lib.*
import kotlin.test.*

class JevaTokenizerUnicodeTest {
    private fun tokenizer(special: Map<String, Int>) =
        JevaTokenizer(_m["A" j 65, "B" j 66], emptySeriesOf<Twin<String>>(), special)

    @Test fun addedTokensCannotContainUnpairedSurrogates() {
        for (key in s_["\uD83D", "\uDE00", "\uD83Dx", "x\uDE00"].view) {
            val failure = assertFailsWith<IllegalArgumentException> { tokenizer(_m[key j 400]) }
            assertContains(failure.message.orEmpty(), "Jeva added token")
        }
    }

    @Test fun addedTokenLookupCannotConsumeAnIsolatedSurrogateAfterMutation() {
        val special = _m["<end>" j 400].toMutableMap()
        val tokenizer = tokenizer(special)
        special["\uD83D"] = 401
        special["<end>"] = 402
        runCatching { (tokenizer.special as MutableMap<String, Int>)["\uD83D"] = 403 }
        runCatching { (tokenizer.special as MutableMap<String, Int>)["<end>"] = 404 }
        assertFailsWith<IllegalArgumentException> { tokenizer.encode("\uD83D") }
        assertEquals(400, tokenizer.encode("<end>")[0])
    }

    @Test fun supplementaryAddedTokensRemainUsable() {
        val tokenizer = tokenizer(_m["<😀>" j 400])
        val tokens = tokenizer.encode("A<😀>B")
        assertEquals(3, tokens.size)
        assertEquals(65, tokens[0])
        assertEquals(400, tokens[1])
        assertEquals(66, tokens[2])
    }
}
