package borg.literbike.ccek.quic

// ============================================================================
// Compatibility Layer -- ported from compat.rs
// CoroutineContext, NetTuple, Protocol enum, Signal, RbCursor stubs
// ============================================================================

/** Protocol types for network tuples */
enum class RbProtocol {
    Tcp,
    Udp,
    Quic,
    Sctp,
    CustomQuic,
    Unknown
}

/** Network tuple -- source/dest address + port + protocol */
data class NetTuple(
    val localAddr: ByteArray = ByteArray(4),
    val localPort: UShort = 0u,
    val remoteAddr: ByteArray = ByteArray(4),
    val remotePort: UShort = 0u,
    val protocol: RbProtocol = RbProtocol.Unknown
) {
    companion object {
        fun fromHostPort(host: String, port: UShort, protocol: RbProtocol = RbProtocol.CustomQuic): NetTuple {
            val addrBytes = host.split(".")
                .takeIf { it.size == 4 }
                ?.mapNotNull { it.toUIntOrNull()?.toByte() }
                ?: ByteArray(4)
            return NetTuple(
                remoteAddr = addrBytes.toByteArray(),
                remotePort = port,
                protocol = protocol
            )
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is NetTuple) return false
        if (!localAddr.contentEquals(other.localAddr)) return false
        if (localPort != other.localPort) return false
        if (!remoteAddr.contentEquals(other.remoteAddr)) return false
        if (remotePort != other.remotePort) return false
        if (protocol != other.protocol) return false
        return true
    }

    override fun hashCode(): Int {
        var result = localAddr.contentHashCode()
        result = 31 * result + localPort.hashCode()
        result = 31 * result + remoteAddr.contentHashCode()
        result = 31 * result + remotePort.hashCode()
        result = 31 * result + protocol.hashCode()
        return result
    }
}

/** Signal types for observational classification (RbCursive) */
enum class RbSignal {
    Accept,
    Reject,
    Unknown
}

/** Stub RbCursor for observational classification */
class RbCursor {
    fun recognize(tuple: NetTuple, data: List<UByte>): RbSignal {
        // Stub: always accept for now
        return RbSignal.Accept
    }
}

/** ContextElement trait for CoroutineContext integration */
interface ContextElement {
    val key: String
}

/**
 * Minimal CoroutineContext -- ported from Rust CoroutineContext stub.
 * Provides typed element storage with merge semantics.
 */
class CoroutineContext private constructor() {
    private val elements = mutableMapOf<String, ContextElement>()

    companion object {
        fun new(): CoroutineContext = CoroutineContext()

        fun <T : ContextElement> withElement(element: T): CoroutineContext {
            val ctx = CoroutineContext()
            ctx.install(element)
            return ctx
        }
    }

    fun get(key: String): ContextElement? = elements[key]

    @Suppress("UNCHECKED_CAST")
    fun <T : ContextElement> getTyped(key: String): T? = get(key) as? T

    fun <T : ContextElement> install(element: T) {
        elements[element.key] = element
    }

    fun merge(other: CoroutineContext): CoroutineContext {
        val merged = CoroutineContext()
        merged.elements.putAll(this.elements)
        for ((k, v) in other.elements) {
            merged.elements.putIfAbsent(k, v)
        }
        return merged
    }

    fun keys(): List<String> = elements.keys.toList()
    fun contains(key: String): Boolean = key in elements
}

/** Indexed trait for ordered collections */
interface Indexed<T> {
    fun get(index: Int): T?
    fun size(): Int
}

/** Join trait for combining contexts */
interface Join<Other : Any, out Result> {
    fun join(other: Other): Result
}
