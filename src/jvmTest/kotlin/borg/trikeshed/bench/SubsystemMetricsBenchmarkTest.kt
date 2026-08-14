package borg.trikeshed.bench

import borg.trikeshed.couch.CouchStoreFactory
import borg.trikeshed.couch.Document
import borg.trikeshed.couch.Field
import borg.trikeshed.cursor.ColumnMeta
import borg.trikeshed.cursor.Cursor
import borg.trikeshed.cursor.IOMemento
import borg.trikeshed.cursor.RowVec
import borg.trikeshed.forge.ForgeApp
import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.*
import borg.trikeshed.parse.confix.*
import borg.trikeshed.userspace.nio.file.spi.JvmFileOperations
import borg.trikeshed.util.oroboros.FileCasStore
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.measureTime

class SubsystemMetricsBenchmarkTest {

    @Test
    fun runAllSubsystemBenchmarks() {
        println("================================================================")
        println("               TRIKESHED SUBSYSTEM METRICS BENCHMARK           ")
        println("================================================================")

        // 1. Kernel Series Lazy Projection (α) vs Eager Materialization
        val nElements = 1_000_000
        val baseSeries: Series<Int> = nElements j { i: Int -> i * 2 }

        // Warmup
        var warmSum = 0L
        for (i in 0 until 10_000) {
            warmSum += baseSeries[i]
        }

        // Benchmark lazy α transformation pipeline
        val lazyTransformTime = measureTime {
            val projected = baseSeries α { it + 1 } α { it * 3 } α { it - 5 }
            var sum = 0L
            for (i in 0 until 100_000) {
                sum += projected[i]
            }
            assertTrue(sum != 0L)
        }

        // Benchmark Series.toList() zero-copy iteration
        val zeroCopyListTime = measureTime {
            val list = baseSeries.toList()
            var sum = 0L
            for (i in 0 until 100_000) {
                sum += list[i]
            }
            assertTrue(sum != 0L)
        }

        println("\n[1] KERNEL ALGEBRA (Series<T> & Projections)")
        println("  • Elements: $nElements integers")
        println("  • Lazy 3-stage α-pipeline traversal (100k reads): $lazyTransformTime")
        println("  • Zero-copy Series.toList() indexing (100k reads): $zeroCopyListTime")

        // 2. Cursor Projection & Fancy Reordering
        val rows = 50_000
        val meta = ColumnMeta("id", IOMemento.IoInt, null)
        val rowVecs: Cursor = (rows j { i: Int ->
            1 j { _: Int -> (i as Any?) j { meta } }
        }) as Cursor

        val pathFixture = IntArray(10_000) { (it * 3) % rows }
        val cursorZoomTime = measureTime {
            for (repeat in 0 until 100) {
                val f: Series<RowVec> = rowVecs
                val reordered: Series<RowVec> = pathFixture.size j { i: Int -> f.b(pathFixture[i]) }
                val sample = (reordered as Cursor)[repeat][0]
                assertTrue(sample != null)
            }
        }
        println("\n[2] CURSOR & DATAFRAME PROJECTION")
        println("  • 50,000 rows × 1 column cursor")
        println("  • 100× Fancy Indexing / Zoom (10,000 rows each): $cursorZoomTime (${cursorZoomTime.inWholeMicroseconds / 100} µs/op)")

        // 3. CAS (Content-Addressed Storage) Ingestion & Retrieval
        val tempDir = Files.createTempDirectory("cas-bench")
        try {
            val cas = FileCasStore(JvmFileOperations(), tempDir.toString())
            val samplePayloads = (0 until 1_000).map { "payload-data-block-number-$it-with-entropy-${it.hashCode()}".encodeToByteArray() }
            val cids = mutableListOf<ContentId>()

            val casPutTime = measureTime {
                for (payload in samplePayloads) {
                    cids.add(cas.put(payload))
                }
            }

            val casGetTime = measureTime {
                var totalBytes = 0L
                for (cid in cids) {
                    val read = cas.get(cid)
                    totalBytes += read?.size ?: 0
                }
                assertTrue(totalBytes > 0)
            }

            println("\n[3] CAS (Content-Addressed Storage)")
            println("  • 1,000 blocks written: $casPutTime (${casPutTime.inWholeMicroseconds / 1000} µs/put)")
            println("  • 1,000 blocks verified & read: $casGetTime (${casGetTime.inWholeMicroseconds / 1000} µs/get)")
        } finally {
            tempDir.toFile().deleteRecursively()
        }

        // 4. CouchStore In-Memory Document Ingestion & Query Throughput
        val store = CouchStoreFactory.inMemory()
        val nDocs = 10_000
        val docIngestTime = measureTime {
            for (i in 0 until nDocs) {
                store.put(Document("doc-$i", listOf(Field("type", if (i % 2 == 0) "A" else "B"), Field("status", if (i % 3 == 0) "active" else "idle"), Field("value", i))))
            }
        }

        var matchCount = 0L
        val queryTime = measureTime {
            for (i in 0 until 1_000) {
                val res = store.query("type", "A")
                matchCount += res.totalCount
            }
        }
        println("\n[4] COUCHSTORE IN-MEMORY ENGINE")
        println("  • Ingestion of $nDocs documents: $docIngestTime (${docIngestTime.inWholeMicroseconds / nDocs} µs/doc)")
        println("  • 1,000 queries over indexed field: $queryTime (${queryTime.inWholeMicroseconds / 1000} µs/query)")

        // 5. Confix Parser & Descriptor Fragment Generation
        val sampleJson = """
        {
          "widget": {
            "debug": "on",
            "window": {
              "title": "Sample Konfix Frame",
              "name": "main_window",
              "width": 500,
              "height": 500
            },
            "image": {
              "src": "Images/Sun.png",
              "name": "sun1",
              "hOffset": 250,
              "vOffset": 250,
              "alignment": "center"
            },
            "text": {
              "data": "Click Here",
              "size": 36,
              "style": "bold",
              "name": "text1",
              "hOffset": 250,
              "vOffset": 100,
              "alignment": "center",
              "onMouseUp": "sun1.opacity = (sun1.opacity / 100) * 90;"
            }
          }
        }
        """.trimIndent()

        val confixParseTime = measureTime {
            for (i in 0 until 5_000) {
                val doc = confixDoc(sampleJson)
                assertTrue(doc.index != null)
            }
        }
        println("\n[5] CONFIX / DESCRIPTOR PARSER")
        println("  • 5,000 JSON/Confix full AST parses: $confixParseTime (${confixParseTime.inWholeMicroseconds / 5000} µs/parse)")

        // 6. ForgeApp Cold-Start HTML Rendering
        val forgeColdStartTime = measureTime {
            for (i in 0 until 1_000) {
                val html = ForgeApp.renderHtml()
                assertTrue(html.isNotEmpty())
            }
        }
        println("\n[6] FORGE UX & WORKSPACE ENGINE")
        println("  • 1,000 ForgeApp.renderHtml() cycles: $forgeColdStartTime (${forgeColdStartTime.inWholeMicroseconds / 1000} µs/render)")

        println("\n================================================================")
        println("                    ALL BENCHMARKS COMPLETED                    ")
        println("================================================================")
    }
}
