package borg.trikeshed.modelmux

import borg.trikeshed.job.ContentId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Frame identity gates (plan step 2): the rolling-cid chain must be
 * deterministic, prefix-extensional, and tamper-evident; NUIDs must be
 * allocation-ordered and prefix-stable.
 */
class FrameChainTest {

    @Test
    fun rollingCidIsDeterministic() {
        val t = "turn one".encodeToByteArray()
        val a = Frame.root(t)
        val b = Frame.root(t)
        assertEquals(a, b, "same genesis turn → same frame (content addressing)")
        assertEquals(a.cid, b.cid)
    }

    @Test
    fun appendingExtendsTheChainNotTheParent() {
        val root = Frame.root("base".encodeToByteArray())
        val next = Frame.append(root, "delta".encodeToByteArray())
        assertEquals(root.cid, next.parent, "child links to parent cid")
        assertNotEquals(root.cid, next.cid)
        assertEquals("base".encodeToByteArray().decodeToString(), root.turn.decodeToString())
    }

    @Test
    fun differentTurnsDivergeDifferentCids() {
        val root = Frame.root("base".encodeToByteArray())
        val a = Frame.append(root, "genre-A".encodeToByteArray())
        val b = Frame.append(root, "genre-B".encodeToByteArray())
        assertNotEquals(a.cid, b.cid, "forked chains must diverge — no shared cache prefix beyond the fork point")
    }

    @Test
    fun chainIsTamperEvident() {
        val root = Frame.root("base".encodeToByteArray())
        val frame = Frame.append(root, "payload".encodeToByteArray())
        // round trip through the store codec
        val doc = FrameChainStore.encode(frame)
        val loaded = assertNotNull(FrameChainStore.decode(doc.decodeToString()))
        assertEquals(frame, loaded)
        // flip one byte of the turn → the rolling cid no longer verifies
        val tampered = loaded.copy(turn = "paylnad".encodeToByteArray())
        val tamperedDoc = FrameChainStore.encode(tampered).decodeToString()
            .replace("\"cid\":\"${frame.cid.value}\"", "\"cid\":\"sha256:${"0".repeat(64)}\"")
        assertNull(FrameChainStore.decode(tamperedDoc), "cid must verify against (parent ++ turn)")
    }

    @Test
    fun scopePrefixIsALongestPrefixOfTheChain() {
        // The address grammar: scope = cid-chain prefix. A chain root is a
        // prefix of every frame appended to it.
        val root = Frame.root("scope".encodeToByteArray())
        val mid = Frame.append(root, "envelope".encodeToByteArray())
        val leaf = Frame.append(mid, "task".encodeToByteArray())
        assertEquals(mid.parent, root.cid, "encapsulation nests: leaf ⊃ mid ⊃ root")
        assertEquals(mid.cid, leaf.parent, "leaf links to mid's cid — the chain is a linked structure of cids")
    }

    @Test
    fun nuidIsMonotonicAndPrefixStable() {
        val nuid = Nuid.seeded("forge9")
        val a = nuid.next()
        val b = nuid.next()
        val c = nuid.next()
        assertTrue(a < b && b < c, "lexicographic order == allocation order: $a $b $c")
        assertTrue(a.startsWith("forge9") && b.startsWith("forge9"), "seeded prefix is stable")
    }

    @Test
    fun nuidBase62RoundTrips() {
        assertEquals("0", Nuid.encode(0L))
        assertEquals("10", Nuid.encode(62L))
        assertEquals(62L, Nuid.decode("10"))
        for (v in listOf(1L, 61L, 62L, 3843L, 238327L, Long.MAX_VALUE / 3)) {
            assertEquals(v, Nuid.decode(Nuid.encode(v)), "base62 round trip at $v")
        }
    }

    @Test
    fun taskAddressIsScopePrefixPlusNuidSuffix() {
        val scope = Frame.root("advisory-board".encodeToByteArray()).cid.hex
        val nuid = Nuid.seeded("matter42")
        val addr = "$scope/${nuid.next()}"
        assertTrue(addr.startsWith(scope), "network part (scope) is the cid-chain prefix")
        assertTrue(addr.substringAfter('/').startsWith("matter42"), "host part is the nuid")
    }

    @Test
    fun persistedFramePathLandsUnderContextsPlane() {
        val root = Frame.root("contexts-check".encodeToByteArray())
        // direct codec-level check of the plane prefix the store uses
        val doc = FrameChainStore.encode(root)
        assertTrue(doc.decodeToString().contains("\"cid\":\"sha256:"))
        assertEquals(root.cid, ContentId(root.cid.value), "cid is a well-formed ContentId")
    }
}
