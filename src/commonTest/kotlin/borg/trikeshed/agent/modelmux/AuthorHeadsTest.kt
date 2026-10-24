package borg.trikeshed.agent.modelmux

import borg.trikeshed.ipns.Ed25519
import borg.trikeshed.ipns.IpnsCid
import borg.trikeshed.ipns.IpnsKeyPair
import borg.trikeshed.ipns.IpnsRecord
import borg.trikeshed.ipns.toIpnsCid
import borg.trikeshed.job.ContentId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Instant

class AuthorHeadsTest {
    private val now = Instant.parse("2026-10-24T12:00:00Z")
    private val later = Instant.parse("2026-10-25T12:00:00Z")
    private val alice = Ed25519.generate()
    private val bob = Ed25519.generate()
    private val v1 = ContentId.of(bytesOf("revision one"))
    private val v2 = ContentId.of(bytesOf("revision two"))
    private val v3 = ContentId.of(bytesOf("revision three"))

    private fun heads() = AuthorHeads(Ed25519) { now }

    private fun record(who: IpnsKeyPair, manifest: ContentId, sequence: ULong, until: Instant = later, value: String = "/ipfs/" + manifest.toIpnsCid(0x55u)): ByteArray =
        IpnsRecord.create(who, value, sequence, until, 300_000_000_000uL, Ed25519).bytes

    @Test
    fun eachAuthorKeepsItsOwnHeadAndNothingIsMerged() {
        val heads = heads()
        assertEquals(HeadDecision.ACCEPTED_FIRST, heads.offer(alice.name, record(alice, v1, 1u)))
        assertEquals(HeadDecision.ACCEPTED_FIRST, heads.offer(bob.name, record(bob, v2, 9u)))
        assertEquals(v1, heads.head(alice.name)!!.manifest)
        assertEquals(v2, heads.head(bob.name)!!.manifest)
        assertEquals(2, heads.all().size, "two authors, two heads: a higher sequence under another name displaces nothing")
        assertNull(heads.head(Ed25519.generate().name))
    }

    @Test
    fun aNewerSequenceMovesTheHeadAndAnOlderRecordCannotMoveItBack() {
        val heads = heads()
        heads.offer(alice.name, record(alice, v1, 1u))
        assertEquals(HeadDecision.ACCEPTED_NEWER, heads.offer(alice.name, record(alice, v2, 2u)))
        val current = heads.head(alice.name)!!
        assertEquals(HeadDecision.REFUSED_OLDER, heads.offer(alice.name, record(alice, v1, 1u)), "an old but perfectly valid record is a downgrade")
        assertEquals(HeadDecision.UNCHANGED, heads.offer(alice.name, record(alice, v2, 2u)))
        assertSame(current, heads.head(alice.name), "nothing was replaced")
        assertEquals(2uL, current.sequence)
    }

    @Test
    fun twoDifferentValuesAtOneSequenceIsEquivocationAndKeepsWhatWasHeld() {
        val heads = heads()
        heads.offer(alice.name, record(alice, v1, 5u))
        assertEquals(HeadDecision.REFUSED_CONFLICT, heads.offer(alice.name, record(alice, v2, 5u)))
        assertEquals(v1, heads.head(alice.name)!!.manifest)
    }

    @Test
    fun recordsThatDoNotVerifyOrDoNotNameARevisionAreRefused() {
        val heads = heads()
        assertEquals(HeadDecision.REFUSED_INVALID, heads.offer(alice.name, record(bob, v1, 1u)), "signed by someone else")
        assertEquals(HeadDecision.REFUSED_INVALID, heads.offer(alice.name, record(alice, v1, 1u, until = Instant.parse("2026-10-23T12:00:00Z"))), "expired")
        val tampered = record(alice, v1, 1u).also { it[it.size / 2] = (it[it.size / 2].toInt() xor 0x55).toByte() }
        assertEquals(HeadDecision.REFUSED_INVALID, heads.offer(alice.name, tampered), "a flipped byte")
        assertEquals(HeadDecision.REFUSED_INVALID, heads.offer(alice.name, byteArrayOf(1, 2, 3)), "garbage")
        assertEquals(HeadDecision.REFUSED_INVALID, heads.offer(alice.name, record(alice, v1, 1u, value = "/ipns/" + bob.name)), "not an /ipfs/ value")
        assertEquals(HeadDecision.REFUSED_INVALID, heads.offer(alice.name, record(alice, v1, 1u, value = "/ipfs/" + IpnsCid(0x70u, IpnsCid.raw(bytesOf("x")).multihash))), "not a raw block")
        assertTrue(heads.all().isEmpty(), "no refusal left a trace")
    }

    @Test
    fun aPinnedTaskHearsOfAMovedHeadOnlyWhenItAsks() {
        val heads = heads()
        heads.offer(alice.name, record(alice, v1, 1u))
        assertNull(heads.updateFor(alice.name, v1), "the head is where the task pinned it")
        heads.offer(alice.name, record(alice, v2, 2u))
        val update = heads.updateFor(alice.name, v1)!!
        assertEquals(v2, update.manifest)
        assertNull(heads.updateFor(alice.name, v2), "once the task adopts v2 there is nothing further to report")
        assertNull(heads.updateFor(bob.name, v1), "an author with no head has no update")
        heads.offer(alice.name, record(alice, v3, 3u))
        assertEquals(v3, heads.updateFor(alice.name, v1)!!.manifest, "updates are the author's current head, never a history the task must replay")
    }

    @Test
    fun onlyRawSha256IpfsValuesAreRevisions() {
        assertEquals(v1, AuthorHeads.revisionOf("/ipfs/" + v1.toIpnsCid(0x55u)))
        assertNull(AuthorHeads.revisionOf("/ipfs/" + v1.toIpnsCid(0x70u)))
        assertNull(AuthorHeads.revisionOf("ipfs/" + v1.toIpnsCid(0x55u)))
        assertNull(AuthorHeads.revisionOf("/ipfs/not-a-cid"))
        assertNull(AuthorHeads.revisionOf(""))
        // The right codec with a digest that is not a sha2-256 of 32 bytes is not a revision either.
        assertNull(AuthorHeads.revisionOf("/ipfs/" + IpnsCid(0x55u, byteArrayOf(0x13, 0x40) + ByteArray(64))), "a 64-byte digest")
        assertNull(AuthorHeads.revisionOf("/ipfs/" + IpnsCid(0x55u, byteArrayOf(0x12, 0x1f) + ByteArray(31))), "a short sha2-256")
    }
}
