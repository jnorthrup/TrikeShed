package borg.trikeshed.narsese

import borg.trikeshed.lib.*
import kotlin.test.*
import kotlin.time.TimeSource

class JevaNormalizationTest {
    @Test fun canonicalOrderAndCompositionMatchUnicodeReference() {
        val cases = _l[
            "e\u0301" j "é", "q\u0301\u0327" j "q\u0327\u0301",
            "\u0301\u0316a" j "\u0316\u0301a", "\u212b" j "Å",
            "\u1100\u1161\u11a8" j "각", "a\u0301\u0300\u0316" j "á\u0316\u0300",
        ]
        for (case in cases) assertEquals(case.b, JevaTokenizer.normalize(case.a), case.a)
    }

    @Test fun longDisorderedCombiningRunHasBoundedClassLookups() {
        val n = 8192
        val input = "a" + "\u0301".repeat(n) + "\u0300".repeat(n) + "\u0316".repeat(n)
        val backing = _m[0x0301 j 230, 0x0300 j 230, 0x0316 j 220]
        val classes = object : Map<Int, Int> by backing {
            var lookups = 0
            override fun get(key: Int): Int? {
                lookups++
                check(lookups <= input.length * 32) { "NFC combining-class lookups exceed the linear bound" }
                return backing[key]
            }
        }
        val elapsed = TimeSource.Monotonic.markNow()
        val result = JevaTokenizer.normalize(input, classes) {
            check(elapsed.elapsedNow().inWholeSeconds < 60) { "NFC normalization exceeded the hang guard" }
        }
        assertEquals("á" + "\u0316".repeat(n) + "\u0301".repeat(n - 1) + "\u0300".repeat(n), result)
    }
}
