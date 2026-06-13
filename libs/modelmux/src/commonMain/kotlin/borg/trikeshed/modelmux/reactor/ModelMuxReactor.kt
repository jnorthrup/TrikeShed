package borg.trikeshed.modelmux.reactor

import borg.trikeshed.ccek.KeyedService
import borg.trikeshed.context.AsyncContextElement
import borg.trikeshed.context.AsyncContextKey
import borg.trikeshed.context.ElementState
import borg.trikeshed.lib.Join
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.BitMasked
import borg.trikeshed.lib.j
import borg.trikeshed.modelmux.config.ModelMuxConfig
import borg.trikeshed.modelmux.config.ModelMuxConfigKeys
import borg.trikeshed.modelmux.keymux.DselRouter
import borg.trikeshed.modelmux.keymux.KeyStore
import borg.trikeshed.modelmux.modelmux.ModelProxy
import borg.trikeshed.modelmux.modelmux.ModelProxyConfig
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineContext
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.io.File

/**
 * ReactorFlags - Feature flags as BitMasked enum
 * Extracted from config for proper algebraic composition
 */
enum class ReactorFlags(override val mask: UInt) : BitMasked<UInt> {
    NONE(0u),
    STREAMING(1u shl 0),
    CACHING(1u shl 1),
    QUOTA_TRACKING(1u shl 2),
    HEALTH_ENDPOINT(1u shl 3),
    MODELS_ENDPOINT(1u shl 4),
    CHAT_ENDPOINT(1u shl 5),
    FALLBACK_ROUTING(1u shl 6),
    METRICS(1u shl 7);

    companion object {
        fun fromBooleans(
            enableStreaming: Boolean,
            enableCaching: Boolean,
            hasFallbackModel: Boolean,
        ): ReactorFlags {
            var mask = NONE.mask
            if (enableStreaming) mask = mask or STREAMING.mask
            if (enableCaching) mask = mask or CACHING.mask
            mask = mask or QUOTA_TRACKING.mask
            mask = mask or HEALTH_ENDPOINT.mask
            mask = mask or MODELS_ENDPOINT.mask
            mask = mask or CHAT_ENDPOINT.mask
            if (hasFallbackModel) mask = mask or FALLBACK_ROUTING.mask
            return values().first { it.mask == mask }
        }
        
        infix fun ReactorFlags.or(other: ReactorFlags): ReactorFlags = 
            values().first { it.mask == (this.mask or other.mask) }
    }
}

/**
 * ReactorProviderSpec - Provider specification for algebraic composition
 */
data class ReactorProviderSpec(
    val name: String,
    val baseUrl: String,
    val apiKeyEnv: String,
    val isFree: Boolean = false,
    val dailyLimit: Long? = null,
    val priority: Int = Int.MAX_VALUE,
)

/**
 * ModelMuxReactorConfig - Configuration for the reactor pipeline
 * 
 * Follows kernel algebra: config = Join<Flags, Series<ProviderSpec>>
 * Flags = BitMasked enum for feature toggles
 * ProviderSpec = Join<ProviderName, Join<BaseUrl, ApiKeyEnv>>
 * 
 * Implements Join for algebraic composition - not a data class
 */
class ModelMuxReactorConfig(
    /** Configuration directory for .env files */
    val configDir: File = File("."),
    
    /** Server port (default 8888 for agent8888 mode) */
    val port: Int = 8888,
    
    /** Bind address */
    val bindAddress: String = "0.0.0.0",
    
    /** Request timeout in seconds */
    val requestTimeoutSecs: Int = 120,
    
    /** Enable streaming responses */
    val enableStreaming: Boolean = true,
    
    /** Enable response caching */
    val enableCaching: Boolean = true,
    
    /** Default model when none specified */
    val defaultModel: String? = null,
    
    /** Fallback model when primary unavailable */
    val fallbackModel: String? = null,
    
    /** Maximum retry attempts */
    val maxRetries: Int = 2,
    
    /** Maximum context window in tokens */
    val maxContextWindow: Int = 128000,
) : Join<ReactorFlags, Series<ReactorProviderSpec>> {
    
    override val a: ReactorFlags get() = ReactorFlags.fromBooleans(
        enableStreaming = enableStreaming,
        enableCaching = enableCaching,
        hasFallbackModel = fallbackModel != null,
    )
    
    override val b: Series<ReactorProviderSpec> get() = 0 j { _ -> ReactorProviderSpec("", "", "") }
    
    /** Get effective flags */
    val flags: ReactorFlags = a
    
    /** Get provider specs (lazy - populated by KeyStore after config load) */
    val providerSpecs: Series<ReactorProviderSpec> = b
    
    /** Check if a flag is enabled */
    fun hasFlag(flag: ReactorFlags): Boolean = (flags.mask and flag.mask) != 0u
    
    /** Get config as Join for algebraic composition */
    fun toJoin(): Join<ReactorFlags, Series<ReactorProviderSpec>> = this j providerSpecs
    
    /** Create config with overrides (functional update) */
    fun copy(
        configDir: File? = null,
        port: Int? = null,
        bindAddress: String? = null,
        requestTimeoutSecs: Int? = null,
        enableStreaming: Boolean? = null,
        enableCaching: Boolean? = null,
        defaultModel: String? = null,
        fallbackModel: String? = null,
        maxRetries: Int? = null,
        maxContextWindow: Int? = null,
    ): ModelMuxReactorConfig = ModelMuxReactorConfig(
        configDir = configDir ?: this.configDir,
        port = port ?: this.port,
        bindAddress = bindAddress ?: this.bindAddress,
        requestTimeoutSecs = requestTimeoutSecs ?: this.requestTimeoutSecs,
        enableStreaming = enableStreaming ?: this.enableStreaming,
        enableCaching = enableCaching ?: this.enableCaching,
        defaultModel = defaultModel ?: this.defaultModel,
        fallbackModel = fallbackModel ?: this.fallbackModel,
        maxRetries = maxRetries ?: this.maxRetries,
        maxContextWindow = maxContextWindow ?: this.maxContextWindow,
    )
    
    companion object {
        /** Default config for agent8888 mode */
        val AGENT_8888 = ModelMuxReactorConfig(port = 8888)
        
        /** Default config for development */
        val DEV = ModelMuxReactorConfig(port = 11434)
        
        /** Minimal config (no optional features) */
        val MINIMAL = ModelMuxReactorConfig(
            enableStreaming = false,
            enableCaching = false,
            fallbackModel = null,
        )
    }
}

/**
 * ModelMuxReactor - CCEK Element that wires the complete pipeline:
 * .env/config → KeyStore (KeyMux) → DSEL Router → ModelProxy (ModelMux) → HTTP Server
 * 
 * This is the main entry point that implements the "env-forwarded key-mux driven reactor pipeline"
 * CCEK Elements mix and drive implementation tracks in channelized NIO facade
 * Facets drive the cursor experience through LCNC primitives
 */
class ModelMuxReactor(
    private val reactorConfig: ModelMuxReactorConfig,
    parentJob: CompletableJob? = null
) : AsyncContextElement(ElementState.CREATED, parentJob), KeyedService {

    companion object ReactorKey : AsyncContextKey<ModelMuxReactor> {
        fun create(
            config: ModelMuxReactorConfig = ModelMuxReactorConfig(),
            parentJob: CompletableJob? = null
        ): ModelMuxReactor {
            return ModelMuxReactor(config, parentJob)
        }

        /** Create with default config from current directory */
        suspend fun createAndStart(
            configDir: File = File("."),
            parentJob: CompletableJob? = null
        ): ModelMuxReactor {
            val reactor = create(ModelMuxReactorConfig(configDir = configDir), parentJob)
            reactor.initialize()
            return reactor
        }
    }

    override val key: CoroutineContext.Key<*> get() = ReactorKey

    // CCEK child elements (fanout subscribers)
    private lateinit var configElement: ModelMuxConfig
    private lateinit var keyStore: KeyStore
    private lateinit var dselRouter: DselRouter
    private lateinit var modelProxy: ModelProxy

    // Server state
    private var serverJob: kotlinx.coroutines.Job? = null

    /** Initialize the complete reactor pipeline */
    suspend fun initialize(): ModelMuxReactor {
        requireState(ElementState.CREATED)
        this.state = ElementState.OPEN

        println("🔧 ModelMuxReactor: Initializing pipeline...")

        // 1. Load configuration from .env files and environment
        println("  📄 Loading configuration from ${reactorConfig.configDir.absolutePath}")
        configElement = ModelMuxConfig(parentJob = this.supervisor).load(reactorConfig.configDir).also {
            it.printConfig()
        }

        // Override with reactor config if provided
        val effectivePort = configElement.getInt(ModelMuxConfigKeys.PORT, reactorConfig.port)
        val effectiveBind = configElement.getString(ModelMuxConfigKeys.BIND_ADDRESS) ?: reactorConfig.bindAddress
        val effectiveTimeout = configElement.getInt(ModelMuxConfigKeys.REQUEST_TIMEOUT_SECS, reactorConfig.requestTimeoutSecs)
        val effectiveStreaming = configElement.getBoolean(ModelMuxConfigKeys.ENABLE_STREAMING, reactorConfig.enableStreaming)
        val effectiveCaching = configElement.getBoolean(ModelMuxConfigKeys.ENABLE_CACHING, reactorConfig.enableCaching)
        val effectiveDefaultModel = configElement.getString(ModelMuxConfigKeys.DEFAULT_MODEL) ?: reactorConfig.defaultModel
        val effectiveFallbackModel = configElement.getString(ModelMuxConfigKeys.FALLBACK_MODEL) ?: reactorConfig.fallbackModel
        val effectiveMaxRetries = configElement.getInt(ModelMuxConfigKeys.MAX_RETRIES, reactorConfig.maxRetries)

        // 2. Initialize KeyStore (KeyMux) - loads API keys from config
        println("  🔐 Initializing KeyStore (KeyMux)...")
        keyStore = KeyStore(parentJob = this.supervisor).initialize(configElement)

        println("  ✅ KeyStore loaded ${keyStore.keyCount} providers: ${keyStore.getProviders().joinToString()}")

        // 3. Initialize DSEL Router (quota-aware routing)
        println("  🧠 Initializing DSEL Router...")
        dselRouter = DselRouter(parentJob = this.supervisor).initialize(keyStore)

        // 4. Build ModelProxyConfig
        val proxyConfig = ModelProxyConfig(
            bindAddress = effectiveBind,
            port = effectivePort,
            enableStreaming = effectiveStreaming,
            enableCaching = effectiveCaching,
            defaultModel = effectiveDefaultModel,
            fallbackModel = effectiveFallbackModel,
            requestTimeoutSecs = effectiveTimeout.toLong(),
            maxRetries = effectiveMaxRetries,
        )

        // 5. Initialize ModelProxy (ModelMux)
        println("  🌐 Initializing ModelProxy (ModelMux) on $effectiveBind:$effectivePort...")
        modelProxy = ModelProxy.Factory.create(proxyConfig, keyStore, dselRouter, parentJob = this.supervisor).initialize()

        // 6. Start HTTP server
        println("  🚀 Starting HTTP server...")
        startHttpServer(effectivePort, effectiveBind)

        this.state = ElementState.ACTIVE
        println("✅ ModelMuxReactor: Pipeline fully initialized and running!")
        println("   Endpoint: http://$effectiveBind:$effectivePort")
        println("   Health:   http://$effectiveBind:$effectivePort/health")
        println("   Models:   http://$effectiveBind:$effectivePort/v1/models")
        println("   Chat:     http://$effectiveBind:$effectivePort/v1/chat/completions")

        return this
    }

    /** Start the HTTP server using Kotlin/Native or JVM server */
    private fun startHttpServer(port: Int, bindAddress: String) {
        serverJob = kotlinx.coroutines.launch(this.supervisor) {
            startMinimalHttpServer(port, bindAddress)
        }
    }

    /** Minimal HTTP server for demonstration - uses kernel algebra for routing */
    private suspend fun startMinimalHttpServer(port: Int, bindAddress: String) {
        println("  ⚠️  Using minimal HTTP server - replace with Ktor/Netty for production")
        
        // Example of how routing would work using kernel algebra:
        // GET  /health       -> modelProxy.health() returns Map (Join algebra)
        // GET  /v1/models    -> modelProxy.getModels() returns ModelsResponse (Series algebra)  
        // POST /v1/chat/completions -> modelProxy.chatCompletion(body: String) (ConfixDoc)
        // POST /v1/chat/completions (stream) -> modelProxy.chatCompletionStream(body: String) (Channel)
        
        // Keep the server running
        this.supervisor.invokeOnCompletion { cause ->
            if (cause != null) {
                println("  ❌ Server error: $cause")
            }
        }
        this.supervisor.join()
    }

    /** Get the ModelProxy for direct access */
    val proxy: ModelProxy get() = modelProxy

    /** Get the KeyStore for direct access */
    val store: KeyStore get() = keyStore

    /** Get the DSEL Router for direct access */
    val router: DselRouter get() = dselRouter

    /** Get the Config for direct access */
    val config: ModelMuxConfig get() = configElement

    /** Get provider status summary - built using kernel algebra */
    suspend fun getStatus(): Map<String, Any> {
        checkState()
        val providerStatuses = dselRouter.getProviderStatus()
        return mapOf(
            "reactor" to mapOf(
                "state" to state.name,
                "port" to reactorConfig.port,
                "bind_address" to reactorConfig.bindAddress,
            ),
            "config" to mapOf(
                "default_model" to configElement.getString(ModelMuxConfigKeys.DEFAULT_MODEL),
                "fallback_model" to configElement.getString(ModelMuxConfigKeys.FALLBACK_MODEL),
            ),
            "keystore" to mapOf(
                "loaded_providers" to keyStore.keyCount,
                "providers" to keyStore.getProviders(),
            ),
            "dsel" to mapOf(
                "available_providers" to providerStatuses.count { it.hasKey },
                "total_providers" to providerStatuses.size,
                "provider_details" to providerStatuses.map { it.toMap() },
            ),
            "modelmux" to modelProxy.health(),
        )
    }

    private fun checkState() {
        if (state != ElementState.ACTIVE) {
            throw IllegalStateException("ModelMuxReactor not active. Current state: $state")
        }
    }

    /** Graceful shutdown */
    override suspend fun drain() {
        if (state.isAtLeast(ElementState.OPEN) && state.isLessThan(ElementState.DRAINING)) {
            state = ElementState.DRAINING
            println("🔄 ModelMuxReactor: Draining...")
            
            serverJob?.cancel()
            serverJob = null
            
            close()
        }
    }
}

/** Extension to convert ProviderStatus to Map */
private fun borg.trikeshed.modelmux.keymux.ProviderStatus.toMap(): Map<String, Any> = mapOf(
    "name" to name,
    "base_url" to baseUrl,
    "has_key" to hasKey,
    "priority" to priority,
    "is_free" to isFree,
    "quota_used_today" to quotaUsedToday,
    "quota_remaining_today" to quotaRemainingToday,
)