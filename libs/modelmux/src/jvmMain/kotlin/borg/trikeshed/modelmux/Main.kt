package borg.trikeshed.modelmux

import borg.trikeshed.context.AsyncContextElement
import borg.trikeshed.context.ElementState
import borg.trikeshed.modelmux.config.ModelMuxConfig
import borg.trikeshed.modelmux.reactor.ModelMuxReactor
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * ModelMux Main Entry Point
 * 
 * Usage:
 *   modelmux                    # Start with defaults (loads .env files from cwd)
 *   modelmux --config-dir /path # Start with config from specific directory
 *   modelmux --port 9999        # Override port
 *   modelmux --health           # Health check and exit
 *   modelmux --list-models      # List available models and exit
 */
fun main(args: Array<String>) = runBlocking {
    val configDir = File(args.firstOrNull { it.startsWith("--config-dir=") }?.substringAfter("=") ?: ".")
    val port = args.firstOrNull { it.startsWith("--port=") }?.substringAfter("=")?.toIntOrNull() ?: 8888
    val healthCheck = args.contains("--health")
    val listModels = args.contains("--list-models")

    println("🚀 ModelMux v0.1.0 - CCEK Model Multiplexer")
    println("   Config dir: ${configDir.absolutePath}")
    println("   Port: $port")

    // Create reactor with config
    val reactor = ModelMuxReactor.create(
        borg.trikeshed.modelmux.reactor.ModelMuxReactorConfig(
            configDir = configDir,
            port = port,
        )
    )

    // Initialize the full pipeline
    reactor.initialize()

    if (healthCheck) {
        val status = reactor.getStatus()
        println("\n📊 Health Status:")
        println(status.entries.joinToString("\n") { "${it.key}: ${it.value}" })
        return@runBlocking
    }

    if (listModels) {
        val models = reactor.proxy.getModels().await()
        println("\n📋 Available Models (${models.data.size}):")
        models.data.forEach { println("  ${it.id} (${it.owned_by})") }
        return@runBlocking
    }

    // Keep running until shutdown
    println("\n✅ ModelMux running. Press Ctrl+C to stop.")
    println("   Endpoints:")
    println("     GET  http://localhost:$port/health")
    println("     GET  http://localhost:$port/v1/models")
    println("     POST http://localhost:$port/v1/chat/completions")

    // Wait for shutdown signal
    reactor.supervisor.join()
    
    println("\n👋 ModelMux stopped")
}