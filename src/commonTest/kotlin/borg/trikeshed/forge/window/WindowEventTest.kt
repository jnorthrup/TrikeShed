package borg.trikeshed.forge.window

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class WindowEventTest {

    @Test
    fun eventRejectsBlankType() {
        assertFailsWith<IllegalArgumentException> {
            WindowEvent(type = "", payload = "x", timestampMillis = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            WindowEvent(type = "   ", payload = "x", timestampMillis = 0)
        }
    }

    @Test
    fun eventRejectsOversizedType() {
        val oversizedType = "a".repeat(129)
        assertFailsWith<IllegalArgumentException> {
            WindowEvent(type = oversizedType, payload = "x", timestampMillis = 0)
        }
    }

    @Test
    fun eventRejectsOversizedPayload() {
        val oversizedPayload = "a".repeat(1_048_577)
        assertFailsWith<IllegalArgumentException> {
            WindowEvent(type = "t", payload = oversizedPayload, timestampMillis = 0)
        }
    }

    @Test
    fun eventAcceptsOneMegabytePayload() {
        val oneMegabytePayload = "a".repeat(1_048_576)
        val event = WindowEvent(type = "t", payload = oneMegabytePayload, timestampMillis = 0)
        assertEquals(oneMegabytePayload, event.payload)
    }

    @Test
    fun eventPreservesTimestamp() {
        val ts = 12345L
        val event = WindowEvent(type = "t", payload = "p", timestampMillis = ts)
        assertEquals(ts, event.timestampMillis)
    }

    @Test
    fun eventEqualityByAllFields() {
        val event1 = WindowEvent(type = "t", payload = "p", timestampMillis = 1)
        val event2 = WindowEvent(type = "t", payload = "p", timestampMillis = 1)
        assertEquals(event1, event2)
    }
}
