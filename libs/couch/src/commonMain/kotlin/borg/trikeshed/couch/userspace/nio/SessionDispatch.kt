package borg.trikeshed.couch.userspace.nio

import borg.trikeshed.couch.htx.HtxBlock
import kotlinx.coroutines.coroutineScope
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Session-sticky routing: messages are routed to the correct session context
 * based on sessionId, then tag-based dispatch within each session handler.
 *
 * SessionContext IS a CoroutineContext.Element keyed by SessionContextKey.
 * It carries the session's handler registry.  The session context is
 * injected into all session-scoped coroutines via the coroutine context.
 */
class SessionContext(
    val sessionId: String,
    override val key: CoroutineContext.Key<SessionContext> = SessionContextKey,
) : AbstractCoroutineContextElement(key) {

    private val _handlers = mutableMapOf<String, MessageHandler>()

    /**
     * Register a handler for a given tag (HtxBlockType.name).
     */
    fun register(tag: String, handler: MessageHandler) {
        _handlers[tag] = handler
    }

    /**
     * Dispatch a block to its registered handler by tag.
     * Idempotent if no handler is registered for the tag.
     */
    suspend fun dispatch(block: HtxBlock): Unit = coroutineScope {
        val tag = block.blockType.name
        val handler = _handlers[tag] ?: return@coroutineScope
        handler.handle(block)
    }

    /** Look up the handler for a given tag. */
    fun handler(tag: String): MessageHandler? = _handlers[tag]
}

/** Singleton key for SessionContext in CoroutineContext. */
object SessionContextKey : CoroutineContext.Key<SessionContext>

/**
 * Handler for a single tag (HtxBlockType.name).
 * The session context is accessed from the coroutine context, not passed as a parameter.
 */
abstract class MessageHandler {
    abstract suspend fun handle(block: HtxBlock)
}
