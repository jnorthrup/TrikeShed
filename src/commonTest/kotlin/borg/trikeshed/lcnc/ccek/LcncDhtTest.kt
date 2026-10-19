package borg.trikeshed.lcnc.ccek

import borg.trikeshed.ipns.*
import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.*
import kotlin.test.*
import kotlin.time.Instant

class LcncDhtTest {
    @Test fun signedDirectoryCarriesExactHostScopeAndConstructionVersions() {
        val programs = 12 j { i: Int -> LcncDhtProgram("council-$i", ContentId.of("program-$i".encodeToByteArray()), "local.council.$i") }
        val host = LcncDhtHost("https://council.example", programs)
        val path = LcncDht.path(host)
        assertTrue(path.length > 2048, "A bounded directory is larger than a peer ID")
        val identity = Ed25519.generate()
        val now = Instant.parse("2026-10-18T12:00:00Z")
        val record = IpnsRecord.create(identity, path, 1u, Instant.parse("2026-10-19T12:00:00Z"), 60_000_000_000u, Ed25519)
        val verified = IpnsRecord.verify(identity.name, record.bytes, now, Ed25519)
        val decoded = LcncDht.decode(verified.value)
        assertEquals(host.cid, decoded.cid)
        assertEquals(host.origin, decoded.origin)
        assertEquals(programs.view.toList(), decoded.programs.view.toList())
        val tampered = record.bytes.also { it[10] = (it[10].toInt() xor 1).toByte() }
        assertFailsWith<IllegalArgumentException> { IpnsRecord.verify(identity.name, tampered, now, Ed25519) }
        assertFailsWith<IllegalArgumentException> { IpnsRecord.verify(Ed25519.generate().name, record.bytes, now, Ed25519) }
    }

    @Test fun discoveryCannotHideAnUnpinnedOrAmbiguousProgramOrInsecureRemoteOrigin() {
        val program = LcncDhtProgram("council", ContentId.of(byteArrayOf(1)), "local.council")
        assertFailsWith<IllegalArgumentException> { LcncDhtHost("https://host.example", s_[program, program]) }
        for (origin in s_["http://host.example", "https://user@host.example", "https://host.example/path", "https://host.example?target=elsewhere"].view) {
            assertFailsWith<IllegalArgumentException> { LcncDhtHost(origin, s_[program]) }
        }
        assertFailsWith<IllegalArgumentException> { LcncDht.decode("/ipfs/" + IpnsCid.raw("not an embedded directory".encodeToByteArray())) }
        assertEquals("http://127.0.0.1:8888", LcncDhtHost("http://127.0.0.1:8888/", s_[program]).origin)
        assertEquals("http://[::1]:8888", LcncDhtHost("http://[::1]:8888", s_[program]).origin)
    }
}
