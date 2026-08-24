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
}
