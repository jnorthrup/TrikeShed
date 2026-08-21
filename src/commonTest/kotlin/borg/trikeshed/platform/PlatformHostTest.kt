package borg.trikeshed.platform

import borg.trikeshed.cursor.currentTimeMillis
import kotlin.test.Test
import kotlin.test.assertEquals

class PlatformHostTest {
    @Test
    fun testFakePlatformHostObservation() {
        val fakeHost = object : PlatformHost {
            override val clock = object : PlatformClock {
                override fun nowMillis(): Long = 9999L
                override fun monotonicNanos(): Long = 8888L
            }
            override val processors: Int = 42
        }

        PlatformHost.register(fakeHost)

        assertEquals(9999L, currentTimeMillis())
    }
}
