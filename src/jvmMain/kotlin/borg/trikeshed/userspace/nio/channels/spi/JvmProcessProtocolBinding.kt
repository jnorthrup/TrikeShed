package borg.trikeshed.userspace.nio.channels.spi

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlin.coroutines.CoroutineContext

/** JVM effects for the common process owner; the caller supplies the complete child environment. */
class JvmProcessProtocolBinding(
    command: List<String>,
    environment: Map<String, String>,
) : ProcessProtocolBinding {
    override val context: CoroutineContext = Dispatchers.Default
    override val pipes: ProcessProtocolPipes = await {
        JvmProcessProtocolPipes.open(
            command = { _, _ -> command },
            environment = environment,
            inheritError = true,
        )
    }

    override fun <T> await(operation: suspend () -> T): T = runBlocking { operation() }

    override suspend fun <T> invoke(operation: () -> T): T = processHostCall(operation)
}
