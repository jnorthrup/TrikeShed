package borg.trikeshed.jules.sync

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class ConflictResolverTest {

    @Test
    fun testLastWriterWins() {
        val msg1 = SyncMessage("1", 1L, "client-a", JsonPrimitive("old"), 1000L)
        val msg2 = SyncMessage("1", 1L, "client-b", JsonPrimitive("new"), 2000L)

        // msg2 has newer timestamp
        val resolved = ConflictResolver.resolve(msg1, msg2, ResolutionStrategy.LAST_WRITER_WINS)
        assertSame(msg2, resolved)

        // Same timestamp, fallback to clientId lexicographically
        val msg3 = SyncMessage("1", 1L, "client-c", JsonPrimitive("newer"), 2000L)
        val resolvedSameTime = ConflictResolver.resolve(msg2, msg3, ResolutionStrategy.LAST_WRITER_WINS)
        assertSame(msg3, resolvedSameTime)
    }

    @Test
    fun testMergeObjects() {
        val obj1 = JsonObject(mapOf("key1" to JsonPrimitive("val1"), "key2" to JsonPrimitive("val2-old")))
        val obj2 = JsonObject(mapOf("key2" to JsonPrimitive("val2-new"), "key3" to JsonPrimitive("val3")))

        val msg1 = SyncMessage("1", 1L, "client-a", obj1, 1000L)
        val msg2 = SyncMessage("1", 1L, "client-b", obj2, 2000L)

        val resolved = ConflictResolver.resolve(msg1, msg2, ResolutionStrategy.MERGE)

        val payload = resolved.payload as JsonObject
        assertEquals(3, payload.size)
        assertEquals("val1", (payload["key1"] as JsonPrimitive).content)
        assertEquals("val2-new", (payload["key2"] as JsonPrimitive).content) // msg2 won
        assertEquals("val3", (payload["key3"] as JsonPrimitive).content)
    }
}
