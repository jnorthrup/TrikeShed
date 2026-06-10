package org.xvm.cursor

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import borg.trikeshed.parse.confix.SaxEvent

class Jep466VirtualizationTest {

    @Test
    fun `parses standard classfile without aliases using JEP 466 and SAX events`() {
        // Read the actual Object.class for testing
        val bytes = ClassLoader.getSystemResourceAsStream("java/lang/Object.class")?.readAllBytes() 
            ?: error("Could not find java.lang.Object.class")

        val events = mutableListOf<SaxEvent>()
        Jep466Cursor.walkClassFile(bytes) { event ->
            events.add(event)
        }

        // Object.class will produce Enter, Leave, and multiple events for fields/methods
        assertTrue(events.size > 10, "Should have emitted several SAX events for methods and class structure")
        assertTrue(events.first() is SaxEvent.Enter, "Should start with an Enter event")
        assertTrue(events.last() is SaxEvent.Leave, "Should end with a Leave event")
    }
}