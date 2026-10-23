package borg.trikeshed.skinnerbox

import borg.trikeshed.context.nuid.Subnet
import borg.trikeshed.ipns.Ed25519
import borg.trikeshed.ipns.IpnsKeyPair
import kotlin.test.*

internal fun meshIdentity(seed: Int): IpnsKeyPair = ByteArray(32) { (seed + it).toByte() }.let {
    IpnsKeyPair(Ed25519.publicKey(it), it)
}
internal fun meshPeer(seed: Int, scope: String = "tenant", port: Int = 18000 + seed): MeshPeer =
    MeshPeer(meshIdentity(seed).publicKey, "domain-$seed", "http://127.0.0.1:$port", Subnet.parse(scope))

class MeshProtocolTest {
    @Test fun identityAndTaskRetainTheirOriginalBytes() {
        val key = meshIdentity(1).publicKey
        val peer = MeshPeer(key, "a", "https://a.example", Subnet.parse("tenant"))
        val id = peer.id
        key.fill(0)
        peer.publicKey.fill(0)
        assertEquals(id, peer.id)
        assertContentEquals(meshIdentity(1).publicKey, peer.publicKey)
        val data = byteArrayOf(1, 2, 3)
        val task = MeshTask("tenant.rows", "RETAIN", data)
        val taskId = task.id
        data.fill(9)
        task.payload.fill(9)
        assertEquals(taskId, task.id)
        assertContentEquals(byteArrayOf(1, 2, 3), task.payload)
        assertNotEquals(task.id, MeshTask("tenant.other", "RETAIN", task.payload).id)
        assertNotEquals(task.id, MeshTask("tenant.rows", "READ", task.payload).id)
    }

    @Test fun signaturesBindAuthorityNamespaceNonceAndResponse() {
        val a = meshPeer(1)
        val b = meshPeer(2)
        val task = MeshTask("tenant.rows", "GET", byteArrayOf(7))
        val request = MeshEnvelope.request(a, b, task, meshIdentity(1), 100_000, ByteArray(32) { 3 })
        val restored = MeshEnvelope.decode(request.bytes())
        assertEquals(task.id, restored.verifyRequest(a, b, 100_000).id)
        assertFailsWith<IllegalArgumentException> { restored.verifyRequest(b, a, 100_000) }
        assertFailsWith<IllegalArgumentException> { restored.verifyRequest(a, b, 130_001) }
        val response = MeshEnvelope.response(restored, byteArrayOf(8), meshIdentity(2), 100_001)
        MeshEnvelope.decode(response.bytes()).verifyResponse(request, b, 100_001)
        val other = MeshEnvelope.request(a, b, task, meshIdentity(1), 100_000, ByteArray(32) { 4 })
        assertFailsWith<IllegalArgumentException> { response.verifyResponse(other, b, 100_001) }
        val altered = request.bytes().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        assertFailsWith<IllegalArgumentException> { MeshEnvelope.decode(altered).verifyRequest(a, b, 100_000) }
    }

    @Test fun scopeAndFramingFailBeforeExecution() {
        val a = meshPeer(1)
        val b = meshPeer(2, "other")
        assertFailsWith<IllegalArgumentException> {
            MeshEnvelope.request(a, b, MeshTask("tenant", "GET", byteArrayOf()), meshIdentity(1), 100_000, ByteArray(32))
        }
        for (scope in listOf("", "tenant..rows", "tenant/rows", ".tenant", "tenant.")) {
            assertFailsWith<IllegalArgumentException> { MeshTask(scope, "GET", byteArrayOf()) }
        }
        assertFailsWith<IllegalArgumentException> { MeshTask("tenant", "GET", ByteArray(MeshTask.MAX_PAYLOAD_BYTES + 1)) }
        val request = MeshEnvelope.request(a, meshPeer(2), MeshTask("tenant", "GET", byteArrayOf()),
            meshIdentity(1), 100_000, ByteArray(32))
        assertFailsWith<IllegalArgumentException> { MeshEnvelope.decode(request.bytes() + 0) }
        assertFails { MeshEnvelope.decode(byteArrayOf(0x82.toByte(), 0x9a.toByte(), 0x7f, -1, -1, -1)) }
        assertFails { MeshEnvelope.decode(ByteArray(20) { 0x81.toByte() }) }
        assertFailsWith<IllegalArgumentException> {
            MeshPeer(meshIdentity(1).publicKey, "a", "http://public.example", Subnet.parse("tenant"))
        }
    }
}
