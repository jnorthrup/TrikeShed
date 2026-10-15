package borg.trikeshed.couch

import borg.trikeshed.job.*
import kotlin.test.*

class CouchJobLogTest {
    fun couch(cas: CasStore = CasStore.inMemory()) = Couch("jobs", CouchStoreFactory.casBacked(cas), cas)

    @Test fun reopenPreservesExactSequencesAndOwnedPayloads() {
        val couch = couch()
        val log = CouchJobLog.open(couch, "log")
        val bytes = byteArrayOf(1, 2, 3)
        log.append(1, bytes)
        bytes[0] = 99
        log.append(9_007_199_254_740_993L, byteArrayOf(4))
        val reopened = CouchJobLog.open(couch, "log")
        assertEquals(listOf(1L, 9_007_199_254_740_993L), reopened.replay().map { it.sequence }.toList())
        assertContentEquals(byteArrayOf(1, 2, 3), reopened.replay().first().payload)
        reopened.replay().first().payload[0] = 88
        assertContentEquals(byteArrayOf(1, 2, 3), reopened.toMap()["1"])
        assertFailsWith<IllegalArgumentException> { reopened.append(2, byteArrayOf(5)) }
        assertEquals(log.toMap().keys, reopened.toMap().keys)
    }

    @Test fun staleWriterCannotReplaceAnotherOwnersCommands() {
        val couch = couch()
        val first = CouchJobLog.open(couch, "log")
        val staleEmpty = CouchJobLog.open(couch, "log")
        first.append(1, byteArrayOf(1))
        assertFailsWith<IllegalStateException> { staleEmpty.append(1, byteArrayOf(9)) }
        val stale = CouchJobLog.open(couch, "log")
        first.append(2, byteArrayOf(2))
        assertFailsWith<IllegalStateException> { stale.append(2, byteArrayOf(9)) }
        assertEquals(listOf(1L), stale.replay().map { it.sequence }.toList())
        assertFailsWith<IllegalStateException> { stale.append(3, byteArrayOf(3)) }
        assertEquals(listOf(1L, 2L), CouchJobLog.open(couch, "log").replay().map { it.sequence }.toList())
    }

    @Test fun ambiguousAcknowledgementRequiresReopen() {
        val base = couch()
        val unknown = Couch("jobs", CouchStore(object : CouchIngress {
            override fun putIntent(doc: Document, expectedRev: String?): Boolean {
                check(base.store.put(doc, expectedRev))
                error("ack lost after commit")
            }
            override fun deleteIntent(docId: String, expectedRev: String?) = error("unused")
        }, base.store.head, base.store.changes), base.cas)
        val log = CouchJobLog.open(unknown, "log")
        assertFailsWith<IllegalStateException> { log.append(1, byteArrayOf(1)) }
        assertTrue(log.toMap().isEmpty())
        assertFailsWith<IllegalStateException> { log.append(2, byteArrayOf(2)) }
        assertContentEquals(byteArrayOf(1), CouchJobLog.open(base, "log").replay().single().payload)
    }

    @Test fun unreadableOrSubstitutedCommandCannotReplay() {
        val command = byteArrayOf(1, 2, 3)
        val commandCid = ContentId.of(command)
        var readMode = 0
        val cas = object : CasStore() {
            override fun get(cid: ContentId): ByteArray? = if (cid != commandCid || readMode == 0) super.get(cid)
                else if (readMode == 1) null else byteArrayOf(9)
        }
        val couch = couch(cas)
        CouchJobLog.open(couch, "log").append(1, command)
        for (mode in 1..2) {
            readMode = mode
            assertFailsWith<IllegalStateException> { CouchJobLog.open(couch, "log") }
        }
    }

    @Test fun malformedFrameCannotBecomeAPartialReplay() {
        val couch = couch()
        val cid = couch.cas.put(byteArrayOf(1)).value
        for (sequence in listOf<Any>(1.5, "-1", "9223372036854775808", "1")) {
            val id = "bad-$sequence"
            assertEquals(true, couch.put(id, mapOf("format" to CouchJobLog.FORMAT, "frames" to listOf(
                mapOf("sequence" to "1", "cid" to cid), mapOf("sequence" to sequence, "cid" to cid))), null)["ok"])
            assertFailsWith<IllegalArgumentException> { CouchJobLog.open(couch, id) }
        }
    }

    @Test fun casSyncFailureCannotPublishTheLogHead() {
        val cas = object : CasStore() { override fun sync(): Unit = error("sync refused") }
        val couch = couch(cas)
        val log = CouchJobLog.open(couch, "log")
        assertFailsWith<IllegalStateException> { log.append(1, byteArrayOf(1)) }
        assertNull(couch.docJson("log"))
        assertTrue(log.toMap().isEmpty())
    }
}
