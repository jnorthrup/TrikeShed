package borg.trikeshed.lcnc.editor

import borg.trikeshed.lcnc.isam.LcncBlock
import borg.trikeshed.lcnc.ccek.IngestStateElement
import borg.trikeshed.lcnc.reactor.ReactorAction
import borg.trikeshed.lib.j
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BlockEditorTest {
    @Test
    fun testBlockEditorRendersTree() = runTest {
        val child1 = LcncBlock(id = "c1", type = "paragraph", parentId = "p1", content = "Child 1")
        val child2 = LcncBlock(id = "c2", type = "paragraph", parentId = "p1", content = "Child 2")
        val parent = LcncBlock(
            id = "p1", 
            type = "group", 
            parentId = null, 
            children = 2 j { i -> if (i == 0) child1 else child2 },
            content = "Parent"
        )
        
        val ingestState = IngestStateElement("test")
        val editor = BlockEditor(parent, ingestState)
        
        val html = editor.renderHtml()
        assertTrue(html.contains("Child 1"))
        assertTrue(html.contains("Child 2"))
        assertTrue(html.contains("class=\"lcnc-block\""))
        assertTrue(html.contains("data-block-id=\"c1\""))
        
        // Ensure menu and indent controls are rendered
        assertTrue(html.contains("lcncIndentBlock"))
        assertTrue(html.contains("lcncOutdentBlock"))
        assertTrue(html.contains("lcncChangeBlockType"))
        assertTrue(html.contains("value=\"paragraph\""))
    }
    
    @Test
    fun testBlockEditorEmitsUpdates() = runTest {
        val block = LcncBlock(id = "b1", type = "paragraph", parentId = null, content = "Hello")
        val ingestState = IngestStateElement("test")
        val editor = BlockEditor(block, ingestState)
        
        editor.open()
        
        val action = ingestState.fanout.receive()
        assertTrue(action is ReactorAction.Opened)
        
        editor.activate()
        assertTrue(ingestState.fanout.receive() is ReactorAction.Activated)

        editor.updateContent("World")
        val updateAction = ingestState.fanout.receive()
        assertTrue(updateAction is ReactorAction.PublishEntity)
        assertEquals("World", (updateAction.entity as LcncBlock).content)
        
        editor.drain()
        assertTrue(ingestState.fanout.receive() is ReactorAction.Draining)

        editor.close()
        val closeAction = ingestState.fanout.receive()
        assertTrue(closeAction is ReactorAction.Closed)
    }
}
