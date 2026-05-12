package borg.trikeshed.combined

import borg.trikeshed.context.AsyncContextElement
import borg.trikeshed.context.AsyncContextKey
import kotlinx.coroutines.channels.Channel

data class CombinedSwitches(
    val continueDownload: Boolean = false,
    val saveNotFound: Boolean = false,
    val dir: CharSequence = ".",
) {
    companion object {
        fun parse(args: List<CharSequence>): CombinedSwitches {
            var continueDownload = false
            var saveNotFound = false
            var dir = "."
            var i = 0
            while (i < args.size) {
                val arg = args[i]
                when {
                    arg == "-c" -> continueDownload = true
                    arg == "--save-not-found" -> {
                        val next = args.getOrNull(i + 1)
                        if (next != null && !next.toString().startsWith("-")) {
                            i++
                            saveNotFound = next.toString().toBooleanStrictOrNull() ?: false
                        } else {
                            saveNotFound = true
                        }
                    }
                    arg.startsWith("--save-not-found=") -> saveNotFound = (""+arg).substringAfter("=").toBooleanStrictOrNull() ?: true; arg == "-d" -> {
                        i++
                        dir = args.getOrNull(i)?.toString() ?: "."
                    }
                }
                i++
            }
            return CombinedSwitches(continueDownload, saveNotFound, dir)
        }
    }
}

class CombinedClientApp(
    private val args: List<CharSequence> = emptyList(),
    private val combinedClient: CombinedClientElement = CombinedClientElement(),
) : AsyncContextElement() {
    companion object Key : AsyncContextKey<CombinedClientApp>()
    override val key: AsyncContextKey<CombinedClientApp> get() = Key

    val switches: CombinedSwitches = CombinedSwitches.parse(args)

    override suspend fun open() {
        try {
            super.open()
            combinedClient.open()
        } catch (e: RuntimeException) {
            close()
            throw e
        }
    }

    override suspend fun close() {
        combinedClient.close()
        super.close()
    }

    suspend fun startRpcSession(channel: Channel<*>) {
        if (lifecycleState != borg.trikeshed.context.ElementState.OPEN) {
            throw IllegalStateException("CombinedClientApp must be open to start RPC session")
        }
    }
}
