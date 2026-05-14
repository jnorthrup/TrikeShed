package borg.trikeshed.couch.pijul

import borg.trikeshed.couch.htx.*
import java.util.LinkedList

/**
 * KET — Key Exchange & Extension Negotiation.
 *
 * Protocol for announcing and agreeing on extension capabilities between two peers
 * before patch transmission begins. Prevents version mismatch failures and enables
 * opt-in standards extensions (IPFS, conflict strategies, compression).
 *
 * KET is sent as the first HTX frame on any new pijul sync session.
 * The peer responds with KETAck which lists the intersection of supported extensions.
 * If no intersection exists, the session terminates gracefully.
 *
 * Extension IDs are namespaced:
 *   ket:*         — core KET protocol extensions
 *   pijul:*       — pijul core extensions
 *   couch:*       — couch-specific extensions (delta format, IPFS, etc.)
 *   ipfs:*        — IPFS storage extensions
 *   git:*         — git interop extensions
 */

/** KET message types. */
object KetType {
    val KET_OFFER: UByte = 0xD0u  // I support these extensions
    val KET_ACK: UByte = 0xD1u    // I accept these extensions
    val KET_NACK: UByte = 0xD2u   // No compatible extensions
    val KET_REQ: UByte = 0xD3u    // Request a specific extension
}

/** A single capability entry. */
data class Capability(
    val namespace: CharSequence,  // "pijul", "couch", "ipfs", "git", "ket"
    val name: CharSequence,
    val version: Int,
    val params: Map<CharSequence, CharSequence> = emptyMap(),
) {
    fun id(): CharSequence = "$namespace:$name@$version"
}

object StandardCapabilities {
    val CouchDeltaV2 = Capability("couch", "delta", 2)
    val IpfsStoreV1 = Capability("ipfs", "store", 1)

    fun defaults(): List<Capability> = listOf(CouchDeltaV2, IpfsStoreV1)
}

private fun currentTimeMillis(): Long = 0L

/** KET negotiation message — offer, ack, or nack. */
data class KetMessage(
    val type: UByte,
    val capabilities: List<Capability>,
    val sessionId: CharSequence,
    val timestamp: Long,
) {
fun encode(): ByteArray {
        // Simple length-prefixed encoding
        val ba = SimpleByteArrayOutputX()
        ba.writeU32(sessionId.length)
        ba.write(sessionId.encodeToByteArray())
        ba.writeU64(timestamp)
        ba.writeU32(capabilities.size)
        for (cap in capabilities) {
            ba.writeU32(cap.namespace.length)
            ba.write(cap.namespace.encodeToByteArray())
            ba.writeU32(cap.name.length)
            ba.write(cap.name.encodeToByteArray())
            ba.writeU32(cap.version)
            ba.writeU32(cap.params.size)
            for ((k, v) in cap.params) {
                ba.writeU32(k.length)
                ba.write(k.encodeToByteArray())
                ba.writeU32(v.length)
                ba.write(v.encodeToByteArray())
            }
        }
        return ba.toByteArray()
    }

    companion object {
        fun decode(data: ByteArray, type: UByte): KetMessage {
            val br = SimpleByteArrayInputX(data)
            val sidLen = br.readU32().toInt()
            val sessionId = data.copyOfRange(br.pos, (br.pos + sidLen).also { br.pos = it }).decodeToString()
            val timestamp = br.readU64()
            val nCaps = br.readU32().toInt()
            val caps = LinkedList<Capability>()
            repeat(nCaps) {
                val nsLen = br.readU32().toInt()
                val ns = data.copyOfRange(br.pos, (br.pos + nsLen).also { br.pos = it }).decodeToString()
                val nameLen = br.readU32().toInt()
                val name = data.copyOfRange(br.pos, (br.pos + nameLen).also { br.pos = it }).decodeToString()
                val version = br.readU32().toInt()
                val nParams = br.readU32().toInt()
                val params = LinkedHashMap<CharSequence, CharSequence>()
                repeat(nParams) {
                    val kLen = br.readU32().toInt()
                    val k = data.copyOfRange(br.pos, (br.pos + kLen).also { br.pos = it }).decodeToString()
                    val vLen = br.readU32().toInt()
                    val v = data.copyOfRange(br.pos, (br.pos + vLen).also { br.pos = it }).decodeToString()
                    params[k] = v
                }
                caps.add(Capability(ns, name, version, params))
            }
            return KetMessage(type, caps, sessionId, timestamp)
        }
    }
}

private class SimpleByteArrayOutputX {
    private val parts = LinkedList<ByteArray>()
    private var size = 0
    fun write(b: ByteArray) { parts.add(b); size += b.size }
    fun writeU32(v: Int) {
        parts.add(byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())); size += 4
    }
    fun writeU64(v: Long) {
        parts.add(byteArrayOf(
            (v ushr 56).toByte(), (v ushr 48).toByte(), (v ushr 40).toByte(), (v ushr 32).toByte(),
            (v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte(),
        )); size += 8
    }
    fun toByteArray(): ByteArray {
        val result = ByteArray(size)
        var pos = 0
        for (p in parts) { p.copyInto(result, pos); pos += p.size }
        return result
    }
}

private class SimpleByteArrayInputX(private val data: ByteArray) {
    var pos = 0
    fun readU32(): Long =
        (((data[pos++].toInt() and 0xff) shl 24) or ((data[pos++].toInt() and 0xff) shl 16) or
                ((data[pos++].toInt() and 0xff) shl 8) or (data[pos++].toInt() and 0xff)).toLong()
    fun readU64(): Long =
        ((data[pos++].toLong() and 0xff) shl 56) or ((data[pos++].toLong() and 0xff) shl 48) or
                ((data[pos++].toLong() and 0xff) shl 40) or ((data[pos++].toLong() and 0xff) shl 32) or
                ((data[pos++].toLong() and 0xff) shl 24) or ((data[pos++].toLong() and 0xff) shl 16) or
                ((data[pos++].toLong() and 0xff) shl 8) or (data[pos++].toLong() and 0xff)
}
/**
 * KET protocol handler.
 * Manages capability negotiation between two peers.
 */
class KetNegotiator(
    private val sessionId: CharSequence,
    private val myCapabilities: List<Capability>,
) {
    private var negotiatedCaps: List<Capability>? = null
    private var state: KetState = KetState.INIT

    enum class KetState { INIT, OFFERED, ACKNOWLEDGED, FAILED }

    /** Build a KET offer message to send to the peer. */
    fun buildOffer(): KetMessage = KetMessage(
        type = KetType.KET_OFFER,
        capabilities = myCapabilities,
        sessionId = sessionId,
        timestamp = currentTimeMillis(),
    )

    /** Process a KET message from the peer. Returns the response (or null to terminate). */
    fun handleMessage(incoming: KetMessage): KetMessage? {
        when (incoming.type) {
            KetType.KET_OFFER -> return processOffer(incoming)
            KetType.KET_ACK -> return processAck(incoming)
            KetType.KET_NACK -> { state = KetState.FAILED; return null }
            else -> return null
        }
    }

    private fun processOffer(incoming: KetMessage): KetMessage {
        val intersection = myCapabilities.filter { mine ->
            incoming.capabilities.any { peer -> peer.id() == mine.id() }
        }
        if (intersection.isEmpty()) {
            state = KetState.FAILED
            return KetMessage(
                type = KetType.KET_NACK,
                capabilities = emptyList(),
                sessionId = sessionId,
                timestamp = currentTimeMillis(),
            )
        }
        negotiatedCaps = intersection
        state = KetState.ACKNOWLEDGED
        return KetMessage(
            type = KetType.KET_ACK,
            capabilities = intersection,
            sessionId = sessionId,
            timestamp = currentTimeMillis(),
        )
    }

    private fun processAck(incoming: KetMessage): KetMessage? {
        negotiatedCaps = incoming.capabilities
        state = KetState.ACKNOWLEDGED
        return null  // no further response needed
    }

    fun isNegotiated(): Boolean = state == KetState.ACKNOWLEDGED
    fun agreedCapabilities(): List<Capability> = negotiatedCaps ?: emptyList()
}

// --- Varint helpers (inline, no external deps) ---

private object ByteArrayOutputSimple {
    private val buf = ByteArray(4096)
    private var pos = 0
    fun write(b: Byte) { buf[pos++] = b }
    fun writeVarint(v: Int) {
        var value = v
        while (value > 0x7f) {
            write(((value and 0x7f) or 0x80).toByte())
            value = value ushr 7
        }
        write(value.toByte())
    }
    fun toByteArray(): ByteArray = buf.copyOf(pos)
}

private class ByteArrayInputSimple(private val data: ByteArray) {
    private var pos = 0
    fun readVarint(): Int {
        var result = 0
        var shift = 0
        while (pos < data.size) {
            val b = data[pos++].toInt()
            result = result or ((b and 0x7f) shl shift)
            if (b and 0x80 == 0) break
            shift += 7
        }
        return result
    }
    fun readBytes(n: Int): ByteArray {
        val start = pos
        pos += n
        return data.copyOfRange(start, pos)
    }
}
