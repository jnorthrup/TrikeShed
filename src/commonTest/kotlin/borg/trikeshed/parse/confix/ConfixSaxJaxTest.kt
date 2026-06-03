package borg.trikeshed.parse.confix

import borg.trikeshed.cursor.*
import borg.trikeshed.lib.*
import kotlin.test.Test
import kotlin.test.fail

class ConfixSaxJaxTest {

    @Test
    fun `SAX stream should emit Enter and Leave events ordered by confix offsets`() {
        // Arrange
        val doc = confixDoc("""{"a": [1, 2]}""")
        
        // Act
        val events = mutableListOf<SaxEvent>()
        doc.index.saxWalk { event -> events.add(event) }
        
        // Assert
        kotlin.test.assertTrue(events.isNotEmpty(), "Events should be emitted")
        kotlin.test.assertTrue(events.first() is SaxEvent.Enter, "First event should be Enter")
        kotlin.test.assertTrue(events.last() is SaxEvent.Leave, "Last event should be Leave")
    }

    @Test
    fun `JAX inflation should bind Confix DirectChildren to a structural DOM node`() {
        // Arrange
        val doc = confixDoc("""{"a": [1, 2]}""")
        
        // Act
        val node = JaxElement.inflate(doc.index, parentTokenIdx = 0, src = doc.src)
        
        // Assert
        kotlin.test.assertTrue(node.children.size > 0, "Root node should have children")
    }

    @Test
    fun `JAX Element should lazy-load raw byte slices`() {
        val doc = confixDoc("""{"a": [1, 2]}""")
        val node = JaxElement.inflate(doc.index, parentTokenIdx = 0, src = doc.src)
        val bytes = node.bytes()
        kotlin.test.assertTrue(bytes.isNotEmpty(), "Should load raw byte slices")
    }
}
