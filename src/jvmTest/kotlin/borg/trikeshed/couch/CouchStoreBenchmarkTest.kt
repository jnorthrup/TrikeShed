package borg.trikeshed.couch

import org.junit.Test
import kotlin.time.measureTime
import borg.trikeshed.lib.*

class CouchStoreBenchmarkTest {
    @Test
    fun benchmarkQuery() {
        val store = CouchStoreFactory.inMemory()
        for (i in 0 until 10000) {
            store.put(Document("doc-$i", listOf(Field("type", if (i % 2 == 0) "A" else "B"), Field("value", i))))
        }

        var totalCount = 0L
        val t = measureTime {
            for (i in 0 until 1000) {
                val result = store.query("type", "A")
                totalCount += result.totalCount
            }
        }
        println("Baseline Time: $t, Expected Count Total: 5000000, Actual Count: $totalCount")
    }
}
