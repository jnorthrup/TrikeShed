package narchy.spacegraph.util

import kotlin.test.Test
import kotlin.test.assertEquals

// Upstream src/utils/collection.ts has no test file. Expected values are worked by hand from its source.
class CollectionTest {
    @Test fun appendToCreatesBucketOnFirstUseAndAppendsAfter() {
        val buckets = mutableMapOf<String, MutableList<Int>>()
        appendTo(buckets, "a", 1)
        appendTo(buckets, "a", 2)
        appendTo(buckets, "b", 3)
        assertEquals(mapOf("a" to mutableListOf(1, 2), "b" to mutableListOf(3)), buckets)
    }
}
