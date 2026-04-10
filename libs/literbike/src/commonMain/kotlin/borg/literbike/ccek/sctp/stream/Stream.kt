package borg.literbike.ccek.sctp.stream

import java.util.concurrent.atomic.AtomicLong

/**
 * SCTP Stream - ordered data channels
 *
 * This module CANNOT see association or chunk.
 */

/**
 * StreamKey - SCTP stream manager
 */
object StreamKey {
    val FACTORY: () -> StreamElement = { StreamElement() }
}

/**
 * StreamElement - manages SCTP streams
 */
class StreamElement {
    val nextSsn: AtomicLong = AtomicLong(0)
    var maxStreams: UShort = 65535u

    /**
     * Allocate next stream sequence number
     */
    fun nextSsn(): UShort {
        val ssn = nextSsn.getAndIncrement()
        return ssn.toUShort()
    }
}

/**
 * SCTP stream
 */
data class Stream(
    val ssn: UShort,
    var sendSeq: UInt = 0u,
    var recvSeq: UInt = 0u,
    val sendBuffer: MutableList<Byte> = mutableListOf(),
    val recvBuffer: MutableList<Byte> = mutableListOf()
)
