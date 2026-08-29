package borg.trikeshed.modelmux

import borg.trikeshed.job.ContentId
import borg.trikeshed.util.oroboros.OroborosAttachmentRef
import borg.trikeshed.util.oroboros.CouchAttachmentGateway
import borg.trikeshed.util.toLowerHex

/**
 * Frame — one turn of a conversation, content-addressed into a rolling chain:
 *
 *     cid_n = ContentId.of(cid_{n-1} ++ turn_n)
 *
 * The chain IS the scope: concentric LCNC scopes are prefixes of this chain,
 * cache affinity is longest-prefix match against a warm lane, and the address
 * grammar (scope prefix + host-id suffix, minted by `modelmux.defaultSecureIdGenerator`)
 * is the same structure routed three ways. No wall clock is consulted inside
 * the algebra — timestamps are
 * supplied by the caller and live only in the persisted document, never in
 * identity.
 */
data class Frame(
    /** Rolling CID: the hash of parent cid bytes ++ this turn's bytes. Root frames hash the empty parent. */
    val cid: ContentId,
    /** Parent frame cid, or null for a chain root. */
    val parent: ContentId?,
    /** This turn's payload (the canonical bytes the cid was computed over). */
    val turn: ByteArray,
) {
    override fun equals(other: Any?): Boolean =
        other is Frame && cid == other.cid && parent == other.parent && turn.contentEquals(other.turn)

    override fun hashCode(): Int = cid.hashCode() * 31 + turn.contentHashCode()

    companion object {
        /** Root of a chain: the genesis turn hashes over the empty parent. */
        fun root(turn: ByteArray): Frame =
            Frame(cid = ContentId.of(EMPTY_PARENT + turn), parent = null, turn = turn)

        /** Append one turn to [parent]'s chain. */
        fun append(parent: Frame, turn: ByteArray): Frame =
            Frame(cid = ContentId.of(parent.cid.hex.encodeToByteArray() + turn), parent = parent.cid, turn = turn)

        private val EMPTY_PARENT = ByteArray(0)
    }
}

/**
 * Persisted shape of a frame chain under `contexts/<cid>` — the same CAS
 * document plane `lcnc/<name>` uses via [CouchAttachmentGateway]. Content
 * is the frame JSON: identity fields (cid/parent) plus caller-supplied
 * metadata. Deterministic; no wall clock.
 */
object FrameChainStore {

    private const val PREFIX = "contexts/"

    fun encode(frame: Frame, meta: Map<String, String> = emptyMap()): ByteArray {
        val sb = StringBuilder("{\"cid\":\"").append(frame.cid.value)
        sb.append("\",\"parent\":")
        if (frame.parent != null) sb.append('"').append(frame.parent.value).append('"') else sb.append("null")
        sb.append(",\"turn\":\"").append(frame.turn.toLowerHex()).append('"')
        for ((k, v) in meta) {
            sb.append(",\"").append(esc(k)).append("\":\"").append(esc(v)).append('"')
        }
        sb.append('}')
        return sb.toString().encodeToByteArray()
    }

    fun persist(gateway: CouchAttachmentGateway, frame: Frame, meta: Map<String, String> = emptyMap(), nowMs: Long): ContentId {
        val bytes = encode(frame, meta)
        val cid = ContentId.of(bytes)
        gateway.putAttachment(
            OroborosAttachmentRef(
                path = PREFIX + frame.cid.hex,
                contentType = "application/json",
                length = bytes.size.toLong(),
                contentId = cid,
                agentId = "frame-chain",
                revision = cid.hex.take(12),
                sequence = nowMs,
            ),
            bytes,
        )
        return cid
    }

    fun load(gateway: CouchAttachmentGateway, cid: ContentId): Frame? {
        val doc = gateway.getAttachment(PREFIX + cid.hex) ?: return null
        return decode(doc.second.decodeToString())
    }

    /** Parse back a frame produced by [encode]; verifies the rolling-cid algebra on load. */
    fun decode(json: String): Frame? {
        val m = runCatching { borg.trikeshed.parse.json.JsonSupport.parse(json) as? Map<*, *> }.getOrNull() ?: return null
        val cidStr = m["cid"]?.toString() ?: return null
        val turnHex = m["turn"]?.toString() ?: return null
        val parentStr = m["parent"]?.toString()
        val turn = fromLowerHex(turnHex) ?: return null
        val parent = if (parentStr == null || parentStr == "null") null else ContentId(parentStr)
        val cid = runCatching { ContentId(cidStr) }.getOrNull() ?: return null
        val expected = if (parent == null) ContentId.of(ByteArray(0) + turn)
        else ContentId.of(parent.hex.encodeToByteArray() + turn)
        return if (expected == cid) Frame(cid, parent, turn) else null
    }

    private fun esc(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")

    private fun fromLowerHex(hex: String): ByteArray? {
        if (hex.length % 2 != 0) return null
        val out = ByteArray(hex.length / 2)
        for (i in out.indices) {
            val hi = hex[i * 2].digitToIntOrNull(16) ?: -1
            val lo = hex[i * 2 + 1].digitToIntOrNull(16) ?: -1
            if (hi < 0 || lo < 0) return null
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }
}
