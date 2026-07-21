package nexus.config

import java.io.File
import java.io.FileInputStream
import java.util.Properties

// Represents the application's configuration
data class NexusConfig(
    val llmProvider: String = "default_llm",
    val logLevel: String = "INFO",
    val maxRetries: Int = 3,
    // Add other configuration properties here
    val agentId: String = "nexus_agent_001"
)

class NexusConfigBuilder {
    private var llmProvider: String? = null
    private var logLevel: String? = null
    private var maxRetries: Int? = null
    private var agentId: String? = null

    // Potential: Load from a properties file
    fun loadFromFile(file: File): NexusConfigBuilder {
        if (file.exists()) {
            val properties = Properties()
            FileInputStream(file).use { properties.load(it) }

            this.llmProvider = properties.getProperty("llm.provider", this.llmProvider)
            this.logLevel = properties.getProperty("log.level", this.logLevel)
            this.maxRetries = properties.getProperty("agent.maxRetries", this.maxRetries?.toString())?.toIntOrNull() ?: this.maxRetries
            this.agentId = properties.getProperty("agent.id", this.agentId)
        }
        return this
    }
    
    // Potential: Load from environment variables
    fun loadFromEnv(): NexusConfigBuilder {
        System.getenv("NEXUS_LLM_PROVIDER")?.let { this.llmProvider = it }
        System.getenv("NEXUS_LOG_LEVEL")?.let { this.logLevel = it }
        System.getenv("NEXUS_MAX_RETRIES")?.toIntOrNull()?.let { this.maxRetries = it }
        System.getenv("NEXUS_AGENT_ID")?.let { this.agentId = it }
        return this
    }

    // Setter methods for programmatic configuration
    fun withLlmProvider(provider: String): NexusConfigBuilder {
        this.llmProvider = provider
        return this
    }

    fun withLogLevel(level: String): NexusConfigBuilder {
        this.logLevel = level
        return this
    }

    fun withMaxRetries(retries: Int): NexusConfigBuilder {
        this.maxRetries = retries
        return this
    }
    
    fun withAgentId(id: String): NexusConfigBuilder {
        this.agentId = id
        return this
    }

    fun build(): NexusConfig {
        // Use defaults from NexusConfig data class if values are not set
        return NexusConfig(
            llmProvider = llmProvider ?: NexusConfig().llmProvider,
            logLevel = logLevel ?: NexusConfig().logLevel,
            maxRetries = maxRetries ?: NexusConfig().maxRetries,
            agentId = agentId ?: NexusConfig().agentId
        )
    }
}

// Example Usage (could be in App.kt or similar)
// fun main() {
//     val config = NexusConfigBuilder()
//         .loadFromFile(File("nexus.properties")) // Attempt to load from file
//         .loadFromEnv() // Override with environment variables if present
//         .withLogLevel("DEBUG") // Programmatically override
//         .build()
//
//     println("Loaded configuration: \$config")
// }

```
