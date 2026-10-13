package borg.trikeshed.lcnc

import borg.trikeshed.job.*
import borg.trikeshed.narsese.DocumentCuratorCodec
import kotlin.test.*

class DocumentSourceRetentionTest {
    @Test fun inlineAndReferencedTextUseTheSameRetainedIdentity() {
        val cas = CasStore.inMemory()
        val source = DocumentCurationLegos.retainSource(cas,
            mapOf("name" to "synthetic.txt", "text" to "Party A reports 雪."))
        assertEquals(source.originalCid, source.extractedTextCid)
        assertContentEquals(source.text.encodeToByteArray(), cas.get(source.originalCid))
        val reference = DocumentCuratorCodec.source(source) - "text"
        assertEquals(source, DocumentCurationLegos.retainSource(cas, reference))
    }

    @Test fun claimedIdentityMustMatchRetainedBytesWithoutReplacingThem() {
        val cas = CasStore.inMemory()
        val source = DocumentCurationLegos.retainSource(cas, mapOf("name" to "record.txt", "text" to "original"))
        assertFailsWith<IllegalArgumentException> {
            DocumentCurationLegos.retainSource(cas, DocumentCuratorCodec.source(source) + ("text" to "changed"))
        }
        assertContentEquals("original".encodeToByteArray(), cas.get(source.originalCid))
        assertFailsWith<IllegalArgumentException> {
            DocumentCurationLegos.retainSource(cas, mapOf("name" to "record.txt", "text" to "changed",
                "extractedTextCid" to source.extractedTextCid.value))
        }
        assertFailsWith<IllegalArgumentException> {
            DocumentCurationLegos.retainSource(cas, mapOf("name" to "bytes.pdf", "base64" to "eA=="))
        }
    }
}
