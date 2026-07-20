package borg.trikeshed.cas

import borg.trikeshed.job.ContentId
import kotlin.test.*

class CasManifestTest {

    @Test
    fun manifestIsContentEqualWhenOrderDiffers() {
        val cid1 = ContentId.of(byteArrayOf(1))
        val cid2 = ContentId.of(byteArrayOf(2))
        val cid3 = ContentId.of(byteArrayOf(3))

        val manifest1 = CasManifest(listOf(cid1, cid2, cid3).sortedBy { it.value })
        val manifest2 = CasManifest(listOf(cid3, cid1, cid2).sortedBy { it.value })

        assertEquals(manifest1.contentId(), manifest2.contentId())
        assertEquals(manifest1, manifest2)
    }

    @Test
    fun manifestMetadataAffectsCid() {
        val cid1 = ContentId.of(byteArrayOf(1))
        val cid2 = ContentId.of(byteArrayOf(2))
        val cid3 = ContentId.of(byteArrayOf(3))

        val manifest1 = CasManifest(listOf(cid1, cid2, cid3).sortedBy { it.value }, byteArrayOf(1))
        val manifest2 = CasManifest(listOf(cid1, cid2, cid3).sortedBy { it.value }, byteArrayOf(2))

        assertNotEquals(manifest1.contentId(), manifest2.contentId())
        assertNotEquals(manifest1, manifest2)
    }

    @Test
    fun manifestEqualsHashCode() {
        val cid1 = ContentId.of(byteArrayOf(1))
        val cid2 = ContentId.of(byteArrayOf(2))
        val cid3 = ContentId.of(byteArrayOf(3))

        val manifest1 = CasManifest(listOf(cid1, cid2, cid3).sortedBy { it.value })
        val manifest2 = CasManifest(listOf(cid1, cid2, cid3).sortedBy { it.value })

        assertEquals(manifest1, manifest2)
        assertEquals(manifest1.hashCode(), manifest2.hashCode())
    }
}
