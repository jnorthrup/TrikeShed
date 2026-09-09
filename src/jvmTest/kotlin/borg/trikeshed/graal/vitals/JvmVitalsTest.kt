package borg.trikeshed.graal.vitals

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class JvmVitalsTest {
    @Test
    fun snapshotCarriesTheInstrumentCluster() {
        val v = JvmVitals()
        v.start()
        try {
            // provoke some JIT + GC work so the JFR lane has something to see
            var acc = 0L
            for (i in 0 until 3_000_000) acc += (i xor (i shl 3)).toLong()
            System.gc()
            Thread.sleep(1200)
            val s = v.snapshot()
            for (key in listOf("graal", "jfr", "jit", "deopt", "gc", "memory", "classes", "threads", "cpu")) {
                assertNotNull(s[key], "snapshot missing $key (acc=$acc)")
            }
            @Suppress("UNCHECKED_CAST") val graal = s["graal"] as Map<String, Any?>
            assertNotNull(graal["vmName"]); assertNotNull(graal["pid"])
            @Suppress("UNCHECKED_CAST") val jfr = s["jfr"] as Map<String, Any?>
            if (jfr["live"] == true) {
                @Suppress("UNCHECKED_CAST") val jit = s["jit"] as Map<String, Any?>
                assertTrue((jit["compilations"] as Long) >= 0)
            }
            @Suppress("UNCHECKED_CAST") val mem = s["memory"] as Map<String, Any?>
            assertTrue((mem["heapUsed"] as Long) > 0)
        } finally {
            v.stop()
        }
    }

    @Test
    fun stopIsIdempotent() {
        val v = JvmVitals()
        v.start(); v.stop(); v.stop()
        assertEquals(false, v.jfrLive)
    }

    @Test
    fun stopTerminatesRecordingStreamThread() {
        val before = recordingStreamThreads().size
        val v = JvmVitals()
        v.start()
        try {
            if (v.jfrLive) {
                assertEventually("JFR stream thread started") {
                    recordingStreamThreads().size > before
                }
            }
        } finally {
            v.stop()
        }
        assertEventually("JFR stream thread stopped: ${recordingStreamThreads()}") {
            recordingStreamThreads().size <= before
        }
    }

    private fun recordingStreamThreads(): List<String> =
        Thread.getAllStackTraces().keys
            .filter { !it.isDaemon && it.name.startsWith("JFR Event Stream") }
            .map { it.name }
            .sorted()

    private fun assertEventually(message: String, timeoutMs: Long = 4_000, predicate: () -> Boolean) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        while (System.nanoTime() < deadline) {
            if (predicate()) return
            Thread.sleep(50)
        }
        assertTrue(predicate(), message)
    }
}
