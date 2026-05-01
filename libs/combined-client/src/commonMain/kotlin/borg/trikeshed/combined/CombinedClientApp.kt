package borg.trikeshed.combined

import borg.trikeshed.context.AsyncContextElement
import borg.trikeshed.context.AsyncContextKey
import borg.trikeshed.context.ElementState
import borg.trikeshed.htx.client.Aria2Switches
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope

/**
 * The app orchestrates the runtime session RPC job.
 * It parses aria2c arguments to instantiate the underlying CombinedClientElement.
 */
class CombinedClientApp(
    val args: List<String> = emptyList(),
    val combinedClient: CombinedClientElement = CombinedClientElement()
) : AsyncContextElement() {
    companion object Key : AsyncContextKey<CombinedClientApp>()

    override val key: AsyncContextKey<CombinedClientApp> get() = Key

    val switches: Aria2Switches

    init {
        var continueDownload = false
        var saveNotFound = false
        var dir: String? = null

        args.forEachIndexed { i, arg ->
            when (arg) {
                "-c" -> continueDownload = true
                "--save-not-found=true" -> saveNotFound = true
                "-d" -> {
                    if (i + 1 < args.size) {
                        val nextArg = args[i + 1]
                        if (!nextArg.startsWith("-")) {
                            dir = nextArg
                        }
                    }
                }
            }
        }

        switches = Aria2Switches(
            continueDownload = continueDownload,
            saveNotFound = saveNotFound,
            dir = dir
        )
    }

    override suspend fun open() {
        super.open()
        try {
            combinedClient.open()
        } catch (e: Exception) {
            close()
            throw e
        }
    }

    override suspend fun close() {
        combinedClient.close()
        super.close()
    }

    /**
     * Starts an RPC session.
     * Commands sent to [rpcChannel] are processed by the CombinedClientElement.
     * Returns the launched Job which can be cancelled, or closes naturally when the channel is closed.
     */
    fun startRpcSession(rpcChannel: Channel<String>): kotlinx.coroutines.Job {
        requireState(ElementState.OPEN)
        return CoroutineScope(supervisor).launch {
            rpcChannel.consumeEach { commandStr ->
                val parts = commandStr.split(" ")
                if (parts.isNotEmpty()) {
                    val command = parts[0]
                    val cmdArgs = if (parts.size > 1) parts.drop(1) else emptyList()
                    try {
                        val result = combinedClient.executeRpc(command, cmdArgs)
                        println("RPC Result: $result")
                    } catch (e: Throwable) {
                        println("RPC Error processing command '$commandStr': ${e.message}")
                    }
                }
            }
        }
    }
}
