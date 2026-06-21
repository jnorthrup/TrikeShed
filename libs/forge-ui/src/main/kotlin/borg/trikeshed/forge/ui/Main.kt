package borg.trikeshed.forge.ui

/**
 * Main entry point for Forge UI Reactor Server.
 */
fun main() {
    println("Starting Forge UI Reactor Server...")
    ReactorServer.start(8080)
    println("Server running. Press Ctrl+C to stop.")
    
    // Keep main thread alive
    Thread.currentThread().join()
}