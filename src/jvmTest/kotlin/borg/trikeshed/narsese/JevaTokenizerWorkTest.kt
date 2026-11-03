package borg.trikeshed.narsese

import borg.trikeshed.lib.*
import kotlin.test.*

class JevaTokenizerWorkTest {
    @Test fun longUnbrokenInputDoesNotRescanAllPairsForEveryMerge() {
        val length = 16_384
        val ranks = object : HashMap<String, Int>() {
            var lookups = 0
            override fun get(key: String): Int? {
                lookups++
                check(lookups <= length * 4) { "BPE rank lookups exceed the linear bound" }
                return super.get(key)
            }
        }
        val tokenizer = JevaTokenizer(_m["a" j 0, "aa" j 1], s_["a" j "a"], _m.get(), ranks)
        val ids = tokenizer.encode("a".repeat(length), maxLength = length)
        assertEquals(length / 2, ids.size)
        assertEquals(1, ids[0])
        assertEquals(1, ids[ids.size - 1])
    }
}
