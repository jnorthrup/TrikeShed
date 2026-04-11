package borg.literbike.uring

/**
 * Zero-allocation io_uring Facade types.
 * Ported from literbike/src/uring/uring_facade.rs (types only - UringFacade moved to trikeshed platform).
 *
 * Provides data structures for SQ/CQ entries and operation codes,
 * congruent with io_uring and QUIC frame types.
 */

const val SQE_BUFFER_SIZE: Int = 4096
const val SQ_RING_SIZE: Int = 256
const val CQ_RING_SIZE: Int = 512

/**
 * Zero-allocation SQE using fixed-size buffer.
 */
data class SqEntry(
    var opcode: OpCode = OpCode.Read,
    var fd: Int = -1,
    var addr: Long = 0,
    var len: Int = 0,
    var offset: Long = 0,
    var flags: Int = 0,
    var userData: Long = 0,
    var bufIndex: Short = 0,
    var personality: Short = 0,
    var spliceFdIn: Int = -1,
    val buffer: ByteArray = ByteArray(SQE_BUFFER_SIZE),
    var bufferLen: Int = 0
) {
    companion object {
        fun default(): SqEntry = SqEntry()
    }
}

/**
 * Zero-allocation CQE using fixed-size buffer.
 */
data class CqEntry(
    var userData: Long = 0,
    var res: Int = 0,
    var flags: Int = 0,
    val buffer: ByteArray = ByteArray(SQE_BUFFER_SIZE),
    var bufferLen: Int = 0
) {
    companion object {
        fun default(): CqEntry = CqEntry()
    }
}

/**
 * Operation codes - congruent with io_uring and QUIC frame types.
 */
enum class OpCode(val value: Int) {
    // Basic I/O operations
    Read(0),
    Write(1),
    Accept(13),
    Connect(16),

    // Advanced operations (kernel-direct when available)
    EbpfCmd(50),
    KernelDb(51),
    ProtocolParse(52),

    // QUIC-style operations (userspace facade)
    StreamOpen(100),
    StreamWrite(101),
    StreamRead(102),
    StreamClose(103),

    // Betanet-specific operations
    RbCursiveMatch(200),
    NoiseHandshake(201),
    CoverTraffic(202)
}
