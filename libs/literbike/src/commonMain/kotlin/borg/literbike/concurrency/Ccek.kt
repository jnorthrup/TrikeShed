package borg.literbike.concurrency

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.*
import kotlinx.coroutines.flow.*
import kotlin.coroutines.*
import kotlin.coroutines.CoroutineContext

/**
 * CCEK - CoroutineContext Element Key Bundling
 *
 * Based on Kotlin's CoroutineContext pattern from Betanet:
 * - Services implement CoroutineContext.Element interface
 * - Each service has a unique Key for lookup
 * - Contexts are composed using + operator
 * - Services are retrieved via context[Key] syntax
 *
 * Core pattern from BetanetIntegrationDemo.kt:
 * ```kotlin
 * return EmptyCoroutineContext +
 *     dhtService +
 *     protocolDetector +
 *     crdtStorage +
 *     crdtNetwork +
 *     conflictResolver
 * ```
 */

/**
 * A context element that can be stored in CoroutineContext
 * Equivalent to Kotlin's CoroutineContext.Element interface
 */
interface ContextElement : CoroutineContext.Element {
    /**
     * The key for this element
     */
    val key: Key<*>

    /**
     * Get the type name for debugging
     */
    val typeName: String
        get() = this::class.simpleName ?: "Unknown"
}

/**
 * Key for context elements
 */
abstract class ContextKey(val name: String) : CoroutineContext.Key<CoroutineContext.Element>

/**
 * Empty context (like EmptyCoroutineContext in Kotlin)
 */
object EmptyContext : CoroutineContext {
    override fun <E : CoroutineContext.Element> get(key: CoroutineContext.Key<E>): E? = null

    override val isEmpty: Boolean get() = true

    override fun iterator(): Iterator<CoroutineContext.Element> = emptyList<CoroutineContext.Element>().iterator()

    override fun <R> fold(initial: R, operation: (R, CoroutineContext.Element) -> R): R = initial

    override fun minusKey(key: CoroutineContext.Key<*>): CoroutineContext = this

    fun createChannel(bufferSize: Int): Channel<Pair<Any, Any>> = Channel(bufferSize)
}

/**
 * CoroutineContext - composite context holding multiple elements
 * Equivalent to Kotlin's CoroutineContext interface
 */
class CoroutineContextImpl(
    private val elements: Map<String, ContextElement> = emptyMap()
) : CoroutineContext {

    companion object {
        fun new() = CoroutineContextImpl()

        fun withElement(element: ContextElement): CoroutineContextImpl {
            return CoroutineContextImpl(mapOf(element.key.name to element))
        }
    }

    fun get(key: String): ContextElement? = elements[key]

    @Suppress("UNCHECKED_CAST")
    fun <T : ContextElement> getTyped(key: String): T? {
        return elements[key] as? T
    }

    fun contains(key: String): Boolean = key in elements

    fun keys(): Set<String> = elements.keys

    override val isEmpty: Boolean get() = elements.isEmpty()

    override fun <E : CoroutineContext.Element> get(key: CoroutineContext.Key<E>): E? {
        // For standard coroutine context key lookup
        return null
    }

    override fun iterator(): Iterator<CoroutineContext.Element> = elements.values.iterator()

    override fun <R> fold(initial: R, operation: (R, CoroutineContext.Element) -> R): R {
        return elements.values.fold(initial, operation)
    }

    override fun minusKey(key: CoroutineContext.Key<*>): CoroutineContext {
        return CoroutineContextImpl(elements.filterKeys { it != (key as? ContextKey)?.name })
    }

    override fun plus(context: CoroutineContext): CoroutineContext {
        return when (context) {
            is CoroutineContextImpl -> merge(context)
            else -> super.plus(context)
        }
    }

    fun merge(other: CoroutineContextImpl): CoroutineContextImpl {
        val newElements = elements.toMutableMap()
        newElements.putAll(other.elements)
        return CoroutineContextImpl(newElements.toMap())
    }

    operator fun plus(element: ContextElement): CoroutineContextImpl {
        val newElements = elements.toMutableMap()
        newElements[element.key.name] = element
        return CoroutineContextImpl(newElements.toMap())
    }

    override fun toString(): String = "CoroutineContext(elements=${elements.keys})"
}

// Operator overloads for combining contexts using +
operator fun CoroutineContextImpl.plus(element: ContextElement): CoroutineContextImpl {
    val newElements = elements.toMutableMap()
    newElements[element.key.name] = element
    return CoroutineContextImpl(newElements.toMap())
}

operator fun CoroutineContextImpl.plus(other: CoroutineContextImpl): CoroutineContextImpl {
    return merge(other)
}

// EmptyContext + Element
operator fun CoroutineContext.plus(element: ContextElement): CoroutineContextImpl {
    return CoroutineContextImpl.withElement(element)
}

// Helper to create a context with CCEK pattern
fun ccekContext(vararg elements: ContextElement): CoroutineContextImpl {
    val map = elements.associateBy { it.key.name }
    return CoroutineContextImpl(map)
}

/**
 * Example service: Protocol Detector (from BetanetReactorCore.kt)
 */
class ProtocolDetector(
    val name: String = "DefaultProtocolDetector"
) : ContextElement {
    override val key: Key<*> = ProtocolDetectorKey
    override fun get(key: Key<CoroutineContext.Element>): CoroutineContext.Element? = null
    override val isEmpty: Boolean get() = false
    override fun <R> fold(initial: R, operation: (R, CoroutineContext.Element) -> R): R =
        operation(initial, this)

    companion object Key : ContextKey("ProtocolDetector")

    fun detectProtocol(data: ByteArray): DetectionResult {
        if (data.isEmpty()) return DetectionResult.Unknown

        return when (data[0].toInt() and 0xFF) {
            0x16 -> DetectionResult.TLS(TLSVersion.TLS13)
            'G'.code -> if (data.startsWith("GET".toByteArray())) DetectionResult.HTTP(HTTPVersion.HTTP11) else DetectionResult.Unknown
            'P'.code -> if (data.startsWith("POST".toByteArray())) DetectionResult.HTTP(HTTPVersion.HTTP11) else DetectionResult.Unknown
            else -> DetectionResult.Unknown
        }
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean {
        if (size < prefix.size) return false
        for (i in prefix.indices) {
            if (this[i] != prefix[i]) return false
        }
        return true
    }
}

/**
 * Detection results (from BetanetReactorCore.kt)
 */
sealed class DetectionResult {
    object Unknown : DetectionResult()
    data class HTTP(val version: HTTPVersion) : DetectionResult()
    data class QUIC(val version: QUICVersion) : DetectionResult()
    data class TLS(val version: TLSVersion) : DetectionResult()
}

enum class HTTPVersion {
    HTTP10, HTTP11, HTTP2, HTTP3
}

enum class QUICVersion {
    QUICv1, QUICv2
}

enum class TLSVersion {
    TLS12, TLS13
}

/**
 * Example service: DHT Service (from BetanetIPFSCore.kt)
 */
class DHTService(
    val nodeId: String
) : ContextElement {
    override val key: Key<*> = DHTServiceKey
    override fun get(key: Key<CoroutineContext.Element>): CoroutineContext.Element? = null
    override val isEmpty: Boolean get() = false
    override fun <R> fold(initial: R, operation: (R, CoroutineContext.Element) -> R): R =
        operation(initial, this)

    companion object Key : ContextKey("DHTService")
}

/**
 * Example service: CRDT Storage (from BetanetCRDTCore.kt)
 */
class CRDTStorage(
    val storagePath: String
) : ContextElement {
    override val key: Key<*> = CRDTStorageKey
    override fun get(key: Key<CoroutineContext.Element>): CoroutineContext.Element? = null
    override val isEmpty: Boolean get() = false
    override fun <R> fold(initial: R, operation: (R, CoroutineContext.Element) -> R): R =
        operation(initial, this)

    companion object Key : ContextKey("CRDTStorage")
}

/**
 * Example service: CRDT Network
 */
class CRDTNetwork(
    val peerId: String
) : ContextElement {
    override val key: Key<*> = CRDTNetworkKey
    override fun get(key: Key<CoroutineContext.Element>): CoroutineContext.Element? = null
    override val isEmpty: Boolean get() = false
    override fun <R> fold(initial: R, operation: (R, CoroutineContext.Element) -> R): R =
        operation(initial, this)

    companion object Key : ContextKey("CRDTNetwork")
}

/**
 * Example service: Conflict Resolver
 */
class ConflictResolver(
    val strategy: ConflictStrategy = ConflictStrategy.LastWriteWins
) : ContextElement {
    override val key: Key<*> = ConflictResolverKey
    override fun get(key: Key<CoroutineContext.Element>): CoroutineContext.Element? = null
    override val isEmpty: Boolean get() = false
    override fun <R> fold(initial: R, operation: (R, CoroutineContext.Element) -> R): R =
        operation(initial, this)

    companion object Key : ContextKey("ConflictResolver")
}

enum class ConflictStrategy {
    LastWriteWins,
    OperationalTransformation,
    CRDTMerge
}
