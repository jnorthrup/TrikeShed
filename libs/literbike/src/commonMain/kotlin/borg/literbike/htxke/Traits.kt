package borg.literbike.htxke

/**
 * CCEK Traits - declarations on Elements
 *
 * Traits are interfaces that Elements may implement.
 */

// HTX trait - declarations on HtxElement
interface HtxVerifier {
    fun verify(input: ByteArray): Boolean
}

fun HtxElement.asHtxVerifier(): HtxVerifier = object : HtxVerifier {
    override fun verify(input: ByteArray): Boolean = this@asHtxVerifier.verify(input)
}

// QUIC trait - declarations on QuicElement
interface QuicEngine {
    fun send(data: ByteArray)
}

fun QuicElement.asQuicEngine(): QuicEngine = object : QuicEngine {
    override fun send(data: ByteArray) {
        // TODO: implement QUIC send
    }
}

// NIO trait - declarations on NioElement
interface NioReactor {
    fun submit(op: ByteArray)
}

fun NioElement.asNioReactor(): NioReactor = object : NioReactor {
    override fun submit(op: ByteArray) {
        // TODO: implement NIO submit
    }
}

// HTTP trait - declarations on HttpElement
interface HttpHandler {
    fun handle(req: ByteArray): ByteArray
}

fun HttpElement.asHttpHandler(): HttpHandler = object : HttpHandler {
    override fun handle(req: ByteArray): ByteArray {
        // TODO: implement HTTP handler
        return ByteArray(0)
    }
}

// SCTP trait - declarations on SctpElement
interface SctpHandler {
    fun sendChunk(chunk: ByteArray)
}

fun SctpElement.asSctpHandler(): SctpHandler = object : SctpHandler {
    override fun sendChunk(chunk: ByteArray) {
        // TODO: implement SCTP send
    }
}
