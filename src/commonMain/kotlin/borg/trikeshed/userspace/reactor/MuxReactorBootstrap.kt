package borg.trikeshed.userspace.reactor

import borg.trikeshed.userspace.nio.file.spi.FileOperations
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Multiplatform reactor bootstrap that wires the mux reactor to ~/.hermes/auth.json.
 *
 * This is NOT a Hermes Agent feature integration. It is a plain stdlib file
 * read of the local credential pool JSON that Hermes Agent happens to write.
 * No Python, no Hermes CLI, no gateway, no messaging platform.
 */
object MuxReactorBootstrap {

    private const val DEFAULT_AUTH_PATH = "~/.hermes/auth.json"

    private var initJob: Job? = null
    private var scope: CoroutineScope? = null

    /**
     * Initialize the reactor with credentials from ~/.hermes/auth.json.
     *
     * @param config Reactor config (defaults applied if not provided)
     * @param parentJob Optional parent job; if null, a SupervisorJob is created
     * @param fileOps Required FileOperations instance for reading auth.json
     * @param authPath Path to auth.json relative to user.home (default: ~/.hermes/auth.json)
     * @return The started MuxReactorElement
     */
    suspend fun initialize(
        config: MuxReactorConfig = MuxReactorConfig(),
        parentJob: Job? = null,
        fileOps: FileOperations,
        authPath: String = DEFAULT_AUTH_PATH,
    ): MuxReactorElement {
        val job = parentJob ?: SupervisorJob()
        val coroutineScope = CoroutineScope(job + fileOps)
        scope = coroutineScope
        initJob = job

        val reactor = openMuxReactorElement(config = config, parentJob = job)

        // Load credentials from ~/.hermes/auth.json
        val authFile = fileOps.resolvePath(fileOps.cwd(), authPath)
        if (fileOps.exists(authFile)) {
            val poolData = HermesAuthReader.readCredentialPool(fileOps, authFile)
            reactor.loadCredentialPool(poolData)
        }

        // Start the reactor tick loop
        reactor.startLoop(coroutineScope)

        return reactor
    }

    /** Shutdown the reactor and its supervisor job. */
    suspend fun shutdown() {
        initJob?.cancel()
        initJob?.join()
    }

    /** Current reactor instance if initialized. */
    @Suppress("UNUSED_PARAMETER")
    fun currentReactor(): MuxReactorElement? = scope?.coroutineContext[MuxReactorElement.Key]
}
