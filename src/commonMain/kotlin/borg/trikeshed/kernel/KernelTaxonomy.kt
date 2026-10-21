package borg.trikeshed.kernel

import kotlinx.coroutines.currentCoroutineContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.jvm.JvmInline

@JvmInline
value class KernelId(val value: String) {
    init { require(value.isNotBlank() && value.length <= 128) }
}

/** Cellular-kernel ring1 excludes interpreters and model tools. */
enum class KernelCapability { COMPUTE, MODEL, EVALUATION, PYTHON, REPL }
enum class KernelOutcome { SUCCESS, FAILURE }

data class KernelTurn(
    val kernel: KernelId,
    val parent: KernelId?,
    val seat: KernelId,
    val sequence: Long,
    val name: String,
    val capability: KernelCapability,
)

/** Task payloads and model responses remain in their seat; observations carry only control metadata. */
data class KernelReceipt(
    val turn: KernelTurn,
    val outcome: KernelOutcome,
    val elapsedMillis: Long,
    val failure: String?,
)

interface KernelPointcut : AutoCloseable {
    fun dispatch(turn: KernelTurn)
    fun complete(receipt: KernelReceipt)
}

/** Installed by the executive, checked at the actual model/interpreter boundary. */
class KernelExecution(
    val kernel: KernelId,
    val parent: KernelId?,
    val seat: KernelId,
    val capability: KernelCapability,
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<KernelExecution> {
        suspend fun require(capability: KernelCapability) {
            val execution = currentCoroutineContext()[Key] ?: return
            check(execution.capability == capability &&
                (execution.parent == null || capability == KernelCapability.COMPUTE)) {
                "Kernel seat ${execution.seat.value} does not admit $capability"
            }
        }
    }
}

/** The sole handle retained by a parent; the implementation owns its channels and state. */
interface KernelLifecycle {
    val id: KernelId
    suspend fun drain()
}
