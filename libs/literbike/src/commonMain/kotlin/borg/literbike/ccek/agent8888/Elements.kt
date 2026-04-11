package borg.literbike.ccek.agent8888

/**
 * CCEK Element and Key traits - declarations on Elements
 *
 * The CCEK pattern: Key provides const factory for Element creation.
 * Elements are stored in Context (hierarchical COW chain).
 */

/**
 * Element trait - stored in Context
 */
interface Element {
    fun keyType(): Any
    fun asAny(): Any
}

/**
 * Key trait - passive SDK provider
 */
interface Key<E : Element> {
    fun factory(): E
}

/**
 * Context - hierarchical COW chain
 */
sealed class Context {
    object Empty : Context()

    data class Cons(
        val element: Element,
        val tail: Context
    ) : Context()

    companion object {
        fun new(): Context = Empty
    }

    fun <E : Element> plus(element: E): Context = Cons(element, this)

    inline fun <reified K : Key<*>> get(): Element? {
        return getByKey(K::class)
    }

    private fun getByKey(keyType: kotlin.reflect.KClass<*>): Element? = when (this) {
        Empty -> null
        is Cons -> {
            if (element.keyType() == keyType) element
            else tail.getByKey(keyType)
        }
    }

    inline fun <reified K : Key<*>> minus(): Context {
        return minusByKey(K::class)
    }

    private fun minusByKey(keyType: kotlin.reflect.KClass<*>): Context = when (this) {
        Empty -> Empty
        is Cons -> {
            if (element.keyType() == keyType) tail.minusByKey(keyType)
            else Cons(element, tail.minusByKey(keyType))
        }
    }

    fun isEmpty(): Boolean = this is Empty

    fun len(): Int = when (this) {
        Empty -> 0
        is Cons -> 1 + tail.len()
    }
}

// ============================================================================
// Agent8888 Elements
// ============================================================================

/**
 * Agent8888Element - protocol detection state
 */
class Agent8888Element(
    val port: UShort = 8888u
) : Element {
    override fun keyType(): Any = Agent8888Key
    override fun asAny(): Any = this
}

/**
 * Agent8888Key - factory for Agent8888Element
 */
object Agent8888Key : Key<Agent8888Element> {
    override fun factory(): Agent8888Element = Agent8888Element()
}

// ============================================================================
// Protocol Handler Elements
// ============================================================================

/**
 * Protocol detection result handler
 */
sealed class CcekHandlerResult {
    data class Handled(val bytesProcessed: Int) : CcekHandlerResult()
    object NeedMoreData : CcekHandlerResult()
    data class Error(val message: String) : CcekHandlerResult()
}

/**
 * Protocol detector interface
 */
interface CcekProtocolDetector {
    fun detect(buffer: ByteArray): CcekDetectionResult
}

/**
 * Protocol handler interface
 */
interface CcekProtocolHandler {
    suspend fun handle(data: ByteArray): CcekHandlerResult
}

/**
 * Protocol registry element
 */
class CcekProtocolRegistryElement(
    val detectors: MutableMap<Protocol, CcekProtocolDetector> = mutableMapOf(),
    val handlers: MutableMap<Protocol, CcekProtocolHandler> = mutableMapOf()
) : Element {
    override fun keyType(): Any = CcekProtocolRegistryKey
    override fun asAny(): Any = this

    fun registerDetector(protocol: Protocol, detector: CcekProtocolDetector) {
        detectors[protocol] = detector
    }

    fun registerHandler(protocol: Protocol, handler: CcekProtocolHandler) {
        handlers[protocol] = handler
    }
}

/**
 * Protocol registry key
 */
object CcekProtocolRegistryKey : Key<CcekProtocolRegistryElement> {
    override fun factory(): CcekProtocolRegistryElement = CcekProtocolRegistryElement()
}

// Re-export HTTP types from local module
typealias HttpMethod = borg.literbike.ccek.agent8888.HttpMethod

// Protocol element stubs
class HtxElement : Element {
    override fun keyType(): Any = HtxKey
    override fun asAny(): Any = this
}

object HtxKey : Key<HtxElement> {
    override fun factory(): HtxElement = HtxElement()
}

class HttpElement : Element {
    override fun keyType(): Any = HttpKey
    override fun asAny(): Any = this
}

object HttpKey : Key<HttpElement> {
    override fun factory(): HttpElement = HttpElement()
}

class QuicElement : Element {
    override fun keyType(): Any = QuicKey
    override fun asAny(): Any = this
}

object QuicKey : Key<QuicElement> {
    override fun factory(): QuicElement = QuicElement()
}

class SctpElement : Element {
    override fun keyType(): Any = SctpKey
    override fun asAny(): Any = this
}

object SctpKey : Key<SctpElement> {
    override fun factory(): SctpElement = SctpElement()
}

class SshElement : Element {
    override fun keyType(): Any = SshKey
    override fun asAny(): Any = this
}

object SshKey : Key<SshElement> {
    override fun factory(): SshElement = SshElement()
}

class TlsElement : Element {
    override fun keyType(): Any = TlsKey
    override fun asAny(): Any = this
}

object TlsKey : Key<TlsElement> {
    override fun factory(): TlsElement = TlsElement()
}
