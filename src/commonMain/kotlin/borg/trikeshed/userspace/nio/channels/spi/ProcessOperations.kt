package borg.trikeshed.userspace.nio.channels.spi

import kotlin.coroutines.CoroutineContext

/**
 * Platform process spawning abstraction — replaces [borg.trikeshed.process.ProcessShell] expect.
 */
interface ProcessOperations : CoroutineContext.Element {
    companion object Key : CoroutineContext.Key<ProcessOperations>
    override val key: CoroutineContext.Key<*> get() = Key

    fun exec(command: String, vararg args: String): ExecResult
}

data class ExecResult(val exitCode: Int, val stdout: String, val stderr: String)
