package borg.trikeshed.net.channelization

import borg.trikeshed.net.ProtocolId
import kotlin.jvm.JvmInline

/**
 * Opaque identifier for a channel block.
 */
@JvmInline
value class ChannelBlockId(val raw: Long)

/**
 * Sequence number for ordering blocks within a session.
 */
@JvmInline
value class BlockSequence(val value: Long)

/**
 * A block is the minimal unit of data exchanged on a channel.
 *
 * Blocks are opaque payloads with ordering and flow-control metadata.
 * Transport-agnostic and semantic-neutral.
 */
data class ChannelBlock(
    val id: ChannelBlockId,
    val session: ChannelSessionId,
    val sequence: BlockSequence,
    val payload: ByteArray,
    val flags: BlockFlags = BlockFlags.None,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ChannelBlock) return false
        return id == other.id && session == other.session && sequence == other.sequence && flags == other.flags && payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + session.hashCode()
        result = 31 * result + sequence.hashCode()
        result = 31 * result + payload.contentHashCode()
        result = 31 * result + flags.hashCode()
        return result
    }
}

/**
 * Flags controlling block handling.
 */
sealed interface BlockFlags {
    /** No special flags. */
    object None : BlockFlags

    /** Block marks end-of-stream for the session. */
    object EndOfStream : BlockFlags

    /** Block requires acknowledgment before next block. */
    object RequireAck : BlockFlags

    /** Block is a control/frame message, not user data. */
    object Control : BlockFlags

    /** Composite flags. */
    data class Composite(val flags: Set<BlockFlags>) : BlockFlags
}

/**
 * Envelope for ingress/egress carrier of blocks.
 *
 * Wraps a [ChannelBlock] with routing and delivery metadata.
 * This is the minimal carrier type for block exchange above [ChannelSession].
 */
data class ChannelEnvelope(
    val block: ChannelBlock,
    val direction: TransferDirection,
    val protocol: ProtocolId,
    val timestamp: Long = 0L,
)

/**
 * Direction of block transfer.
 */
enum class TransferDirection {
    Ingress,
    Egress,
}

/**
 * Acknowledgment for a received block.
 */
data class BlockAck(
    val sessionId: ChannelSessionId,
    val blockId: ChannelBlockId,
    val sequence: BlockSequence,
    val acknowledged: Boolean,
    val reason: String? = null,
)
