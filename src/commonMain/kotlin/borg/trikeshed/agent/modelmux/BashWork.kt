package borg.trikeshed.agent.modelmux

import borg.trikeshed.lib.*
import borg.trikeshed.userspace.nio.channels.spi.ProcessOperations
import borg.trikeshed.userspace.nio.channels.spi.ProcessResult
import kotlinx.coroutines.currentCoroutineContext

/** Explicit Bash arguments; no interpolation or eval is performed by the harness. */
data class BashWork(val arguments: Series<String>, val environment: Map<String, String> = emptyMap())

/** Runs only within a caller-owned userspace process context. No ambient platform fallback. */
suspend fun executeBashWork(work: borg.trikeshed.agent.modelmux.BashWork): ProcessResult {
    val operations = checkNotNull(currentCoroutineContext()[ProcessOperations.Key]) {
        "Bash mission requires userspace ProcessOperations"
    }
    return operations.exec("bash", work.arguments.toList(), env = work.environment)
}
