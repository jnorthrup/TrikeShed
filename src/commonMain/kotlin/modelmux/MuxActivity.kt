package modelmux

import borg.trikeshed.modelmux.ModelResponseReceipt
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Clock
import kotlinx.serialization.Serializable
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Attribution follows a coroutine through failover without consulting lastReceipt. */
class MuxCallContext(
    val conversationId: Long? = null,
    val turnId: Long? = null,
    val activity: MuxActivity? = null,
    val onFinished: ((MuxCallRecord) -> Unit)? = null,
) :
    AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<MuxCallContext>
}

@Serializable
data class MuxCallRecord(
    val id: Long,
    val conversationId: Long? = null,
    val turnId: Long? = null,
    val assessmentId: String? = null,
    val model: String,
    val provider: String,
    val startedAt: Long,
    val endedAt: Long? = null,
    val status: String = "running",
    val keyId: String? = null,
    val httpStatus: Int? = null,
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    val cachedHit: Boolean = false,
    val cacheReadTokens: Int = 0,
    val cacheWriteTokens: Int = 0,
    val error: String? = null,
)

/** Bounded process telemetry. Active calls survive retention of completed calls. */
class MuxActivity(private val capacity: Int = 1000) {
    private val mutex = Mutex()
    private val calls = linkedMapOf<Long, MuxCallRecord>()
    private var sequence = 0L

    suspend fun start(model: String, provider: String, context: MuxCallContext?, assessmentId: String?): Long = mutex.withLock {
        val id = ++sequence
        calls[id] = MuxCallRecord(id, context?.conversationId, context?.turnId, assessmentId, model, provider, Clock.System.now().toEpochMilliseconds())
        id
    }

    suspend fun finish(id: Long, receipt: ModelResponseReceipt?, keyId: String?, failure: Throwable?) = mutex.withLock {
        val call = calls[id] ?: return@withLock null
        val finished = call.copy(
            endedAt = Clock.System.now().toEpochMilliseconds(),
            status = when {
                failure is kotlinx.coroutines.CancellationException -> "cancelled"
                failure != null -> "failed"
                else -> "completed"
            },
            keyId = keyId,
            httpStatus = receipt?.httpStatus,
            inputTokens = receipt?.inputTokens ?: 0,
            outputTokens = receipt?.outputTokens ?: 0,
            cachedHit = receipt?.cachedHit ?: false,
            cacheReadTokens = receipt?.cacheReadTokens ?: 0,
            cacheWriteTokens = receipt?.cacheWriteTokens ?: 0,
            // Provider bodies may contain credential material; publish only the failure class.
            error = failure?.let { it::class.simpleName },
        )
        calls[id] = finished
        while (calls.size > capacity) {
            val oldest = calls.values.firstOrNull { it.endedAt != null } ?: break
            calls.remove(oldest.id)
        }
        finished
    }

    suspend fun snapshot(): List<MuxCallRecord> = mutex.withLock { calls.values.toList() }
}
