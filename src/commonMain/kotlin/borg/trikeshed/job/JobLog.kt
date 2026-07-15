package borg.trikeshed.job

/**
 * JobLog — Write-Ahead Log for durable job commands.
 *
 * In-memory implementation with frame-CRC simulation and torn-frame injection.
 * Each frame: [sequence(Long)][payloadLen(Int)][payload(bytes)].
 * A "torn frame" has a payload length that exceeds available bytes → replay stops.
 */
open class JobLog private constructor(
    private val frames: MutableList<Frame> = mutableListOf(),
) {

    data class Frame(val sequence: Long, val payload: ByteArray) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Frame) return false
            return sequence == other.sequence && payload.contentEquals(other.payload)
        }
        override fun hashCode(): Int = sequence.hashCode() * 31 + payload.contentHashCode()
    }

    open fun append(sequence: Long, payload: ByteArray) {
        val previous = frames.lastOrNull()?.sequence
        require(previous == null || sequence > previous) {
            "WAL sequence must increase: previous=$previous, attempted=$sequence"
        }
        frames.add(Frame(sequence, payload.copyOf()))
    }

    /**
     * Replay all valid frames, stopping at the first torn/corrupt frame.
     */
    open fun replay(): kotlin.sequences.Sequence<Frame> = kotlin.sequences.sequence {
        for (frame in frames) {
            if (frame.payload.isEmpty()) break
            yield(frame)
        }
    }

    /**
     * Inject a torn frame — simulates a partial write (CRC mismatch on replay).
     */
    open fun injectTornFrame(sequence: Long, payload: ByteArray) {
        frames.add(Frame(sequence, ByteArray(0)))
    }

    fun toMap(): MutableMap<String, ByteArray> {
        val map = mutableMapOf<String, ByteArray>()
        for (frame in frames) {
            if (frame.payload.isNotEmpty()) {
                map["${frame.sequence}"] = frame.payload.copyOf()
            }
        }
        return map
    }

    companion object {
        fun inMemory(): JobLog = JobLog()

        fun fromMap(data: MutableMap<String, ByteArray>): JobLog {
            val log = JobLog()
            data.toSortedMap(compareBy { it.toLongOrNull() ?: 0L }).forEach { (k, v) ->
                val seq = k.toLongOrNull() ?: 0L
                log.append(seq, v)
            }
            return log
        }
    }
}