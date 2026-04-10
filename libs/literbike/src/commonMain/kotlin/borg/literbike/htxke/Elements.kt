package borg.literbike.htxke

/**
 * CCEK Elements - Kotlin CoroutineContext.Element implementations
 */

// HTX -----------------------------------------------------------------------

class HtxElement(
    var connections: UInt = 0u
) : Element {
    override val key: Key<*> get() = HtxKey

    fun verify(packet: ByteArray): Boolean = true
}

object HtxKey : Key<HtxElement>, Job {
    override val key: Key<*> get() = this
    override fun factory(): HtxElement = HtxElement()
    override fun isActive(): Boolean = true
    override fun isCompleted(): Boolean = false
    override fun join() { /* no-op for stub */ }
    override fun cancel() { /* no-op for stub */ }
}

// QUIC ----------------------------------------------------------------------

class QuicElement(
    var connections: UInt = 0u
) : Element {
    override val key: Key<*> get() = QuicKey
}

object QuicKey : Key<QuicElement>, Job {
    override val key: Key<*> get() = this
    override fun factory(): QuicElement = QuicElement()
    override fun isActive(): Boolean = true
    override fun isCompleted(): Boolean = false
    override fun join() { /* no-op for stub */ }
    override fun cancel() { /* no-op for stub */ }
}

// HTTP ----------------------------------------------------------------------

class HttpElement(
    var requests: ULong = 0uL
) : Element {
    override val key: Key<*> get() = HttpKey
}

object HttpKey : Key<HttpElement>, Job {
    override val key: Key<*> get() = this
    override fun factory(): HttpElement = HttpElement()
    override fun isActive(): Boolean = true
    override fun isCompleted(): Boolean = false
    override fun join() { /* no-op for stub */ }
    override fun cancel() { /* no-op for stub */ }
}

// SCTP ----------------------------------------------------------------------

class SctpElement(
    var associations: UInt = 0u
) : Element {
    override val key: Key<*> get() = SctpKey
}

object SctpKey : Key<SctpElement>, Job {
    override val key: Key<*> get() = this
    override fun factory(): SctpElement = SctpElement()
    override fun isActive(): Boolean = true
    override fun isCompleted(): Boolean = false
    override fun join() { /* no-op for stub */ }
    override fun cancel() { /* no-op for stub */ }
}

// NIO ----------------------------------------------------------------------

class NioElement(
    var activeFds: UInt = 0u,
    val maxFds: UInt
) : Element {
    override val key: Key<*> get() = NioKey

    companion object {
        fun new(maxFds: UInt): NioElement = NioElement(0u, maxFds)
    }
}

object NioKey : Key<NioElement>, Job {
    override val key: Key<*> get() = this
    override fun factory(): NioElement = NioElement()
    override fun isActive(): Boolean = true
    override fun isCompleted(): Boolean = false
    override fun join() { /* no-op for stub */ }
    override fun cancel() { /* no-op for stub */ }
}
