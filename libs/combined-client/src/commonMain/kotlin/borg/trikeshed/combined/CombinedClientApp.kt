package borg.trikeshed.combined

import borg.trikeshed.context.AsyncContextElement
import borg.trikeshed.context.AsyncContextKey
import borg.trikeshed.context.ElementState
import borg.trikeshed.htx.client.Aria2Switches
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope

/**
 * The app orchestrates the runtime session RPC job.
 * It parses aria2c arguments to instantiate the underlying CombinedClientElement.
 */
class CombinedClientApp(
    val args: List<String> = emptyList()
) : AsyncContextElement() {
    companion object Key : AsyncContextKey<CombinedClientApp>()

    override val key: AsyncContextKey<CombinedClientApp> get() = Key

    val switches: Aria2Switches
    val combinedClient = CombinedClientElement()

    init {
        var continueDownload = false
        var saveNotFound = false
        var dir: String? = null

        args.forEachIndexed { i, arg ->
            when (arg) {
                "-c" -> continueDownload = true
                "--save-not-found=true" -> saveNotFound = true
                "-d" -> if (i + 1 < args.size) dir = args[i + 1]
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
        combinedClient.open()
    }

    override suspend fun close() {
        combinedClient.close()
        super.close()
    }

    /**
     * Starts an RPC session using a SupervisorJob.
     * Commands sent to [rpcChannel] are processed by the CombinedClientElement.
     */
    suspend fun startRpcSession(rpcChannel: Channel<String>) {
        requireState(ElementState.OPEN)
        supervisorScope {
            launch {
                rpcChannel.consumeEach { commandStr ->
                    val parts = commandStr.split(" ")
                    if (parts.isNotEmpty()) {
                        val command = parts[0]
                        val cmdArgs = if (parts.size > 1) parts.drop(1) else emptyList()
                        try {
                            val result = combinedClient.executeRpc(command, cmdArgs)
                            println("RPC Result: $result")
                        } catch (e: Exception) {
                            println("RPC Error processing command '$commandStr': ${e.message}")
                        }
                    }
                }
            }
        }
    }
}
