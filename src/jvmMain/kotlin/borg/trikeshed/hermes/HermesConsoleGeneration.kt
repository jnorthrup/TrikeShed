package borg.trikeshed.hermes

import borg.trikeshed.agent.modelmux.BreadcrumbGeneration
import kotlinx.coroutines.*
import java.util.UUID
import kotlin.coroutines.CoroutineContext

/** One immutable console guest, host-effect owner and callback fence. */
internal class HermesConsoleGeneration<R : AutoCloseable>(
    context: CoroutineContext,
    parent: Job?,
    val session: String = UUID.randomUUID().toString(),
    private val callbackLock: Any = Any(),
    factory: (HermesConsoleGeneration<R>) -> R,
) : AutoCloseable {
    private val supervisor = SupervisorJob(parent)
    val scope = CoroutineScope(context + Dispatchers.IO + supervisor)
    val crossings = BreadcrumbGeneration(scope, session)
    private val retirement = Any()
    @Volatile private var callbacks = false
    private var retired = false
    val resource: R = try { factory(this) } catch (failure: Throwable) {
        callbacks = false
        runBlocking { crossings.drain() }
        scope.cancel()
        runBlocking { supervisor.join() }
        throw failure
    }

    init { callbacks = true }

    /** The console's state lock makes callback delivery and retirement a single ordered boundary. */
    fun <T> callback(deliver: () -> T): T? = synchronized(callbackLock) {
        if (callbacks) deliver() else null
    }

    /** Refuse callbacks and new effects, drain admitted effects, then close the old guest. */
    fun retire(closeResource: (R) -> Unit) = synchronized(retirement) {
        if (!retired) {
            synchronized(callbackLock) { callbacks = false }
            try {
                runBlocking { crossings.drain() }
                closeResource(resource)
                retired = true
            } finally { scope.cancel(); runBlocking { supervisor.join() } }
        }
    }

    override fun close() = retire(AutoCloseable::close)
}
