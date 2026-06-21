package borg.trikeshed.userspace.reactor

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * JVM-only reactor bootstrap that wires the mux reactor to ~/.hermes/auth.json.
 *
 * This is NOT a Hermes Agent feature integration. It is a plain stdlib file
 * read of the local credential pool JSON that Hermes Agent happens to write.
 * No Python, no Hermes CLI, no gateway, no messaging platform.
 */
object MuxReactorBootstrapJvm {

    private const val DEFAULT_AUTH_PATH = ".hermes/auth.json"

    private var initJob: Job? = null
    private var scope: CoroutineScope? = null

    /**
     * Initialize the reactor with credentials from ~/.hermes/auth.json.
     *
     * @param config Reactor config (defaults applied if not provided)
     * @param parentJob Optional parent job; if null, a SupervisorJob is created
     * @param authPath Path to auth.json relative to user.home (default: ~/.hermes/auth.json)
     * @return The started MuxReactorElement
     */
    suspend fun initialize(
        config: MuxReactorConfig = MuxReactorConfig(),
        parentJob: Job? = null,
        authPath: String = DEFAULT_AUTH_PATH,
    ): MuxReactorElement {
        val job = parentJob ?: SupervisorJob()
        val coroutineScope = CoroutineScope(job)
        scope = coroutineScope
        initJob = job

        val reactor = openMuxReactorElement(config = config, parentJob = job)

        // Load credentials from ~/.hermes/auth.json
        val authFile = Paths.get(System.getProperty("user.home"), authPath)
        if (Files.exists(authFile)) {
            val poolData = HermesAuthReaderJvm.readCredentialPool(authFile)
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
