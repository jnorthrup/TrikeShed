package borg.trikeshed.util.oroboros

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AttachmentDeltaReducerTest {

    @Test
    fun `coalesces path normalizes and CREATE then DELETE drops`() {
        val reducer = AttachmentDeltaReducer(emptyMap())
        reducer.consume(listOf(
            AttachmentEvent("foo\\bar.txt", AttachmentEventType.CREATE),
            AttachmentEvent("foo/bar.txt", AttachmentEventType.DELETE)
        ))
        
        val batch = reducer.takeBatch(10)
        assertTrue(batch.actions.isEmpty(), "Expected CREATE then DELETE to result in no action for unknown file")
    }

    @Test
    fun `coalesces CREATE then MODIFY resolves to CREATE`() {
        val reducer = AttachmentDeltaReducer(emptyMap())
        reducer.consume(listOf(
            AttachmentEvent("file.txt", AttachmentEventType.CREATE),
            AttachmentEvent("file.txt", AttachmentEventType.MODIFY)
        ))
        
        val batch = reducer.takeBatch(10)
        assertEquals(1, batch.actions.size)
        assertEquals(AttachmentAction.CREATE, batch.actions["file.txt"])
    }

    @Test
    fun `MODIFY is ignored if digest matches known state`() {
        val knownState = mapOf("file.txt" to "hash123")
        val reducer = AttachmentDeltaReducer(knownState)
        
        reducer.consume(listOf(
            AttachmentEvent("file.txt", AttachmentEventType.MODIFY, digest = "hash123")
        ))
        
        val batch = reducer.takeBatch(10)
        assertTrue(batch.actions.isEmpty())
    }

    @Test
    fun `MODIFY is kept if digest differs from known state`() {
        val knownState = mapOf("file.txt" to "hash123")
        val reducer = AttachmentDeltaReducer(knownState)
        
        reducer.consume(listOf(
            AttachmentEvent("file.txt", AttachmentEventType.MODIFY, digest = "hash456")
        ))
        
        val batch = reducer.takeBatch(10)
        assertEquals(1, batch.actions.size)
        assertEquals(AttachmentAction.REPLACE, batch.actions["file.txt"])
    }

    @Test
    fun `DELETE is kept if file exists in known state`() {
        val knownState = mapOf("file.txt" to "hash123")
        val reducer = AttachmentDeltaReducer(knownState)
        
        reducer.consume(listOf(
            AttachmentEvent("file.txt", AttachmentEventType.DELETE)
        ))
        
        val batch = reducer.takeBatch(10)
        assertEquals(1, batch.actions.size)
        assertEquals(AttachmentAction.DELETE, batch.actions["file.txt"])
    }

    @Test
    fun `takeBatch limits output and sorts alphabetically`() {
        val reducer = AttachmentDeltaReducer(emptyMap())
        reducer.consume(listOf(
            AttachmentEvent("z.txt", AttachmentEventType.CREATE),
            AttachmentEvent("a.txt", AttachmentEventType.CREATE),
            AttachmentEvent("m.txt", AttachmentEventType.CREATE)
        ))
        
        val batch1 = reducer.takeBatch(2)
        assertEquals(2, batch1.actions.size)
        assertEquals(listOf("a.txt", "m.txt"), batch1.actions.keys.toList())
        
        val batch2 = reducer.takeBatch(2)
        assertEquals(1, batch2.actions.size)
        assertEquals(listOf("z.txt"), batch2.actions.keys.toList())
    }

    @Test
    fun `OVERFLOW clears pending events and requires reconciliation`() {
        val reducer = AttachmentDeltaReducer(emptyMap())
        reducer.consume(listOf(
            AttachmentEvent("a.txt", AttachmentEventType.CREATE),
            AttachmentEvent("", AttachmentEventType.OVERFLOW)
        ))
        
        val batch = reducer.takeBatch(10)
        assertTrue(batch.actions.isEmpty())
        assertTrue(batch.needsReconciliation)
    }
}
