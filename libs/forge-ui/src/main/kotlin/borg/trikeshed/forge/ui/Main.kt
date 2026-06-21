package borg.trikeshed.forge.ui

/**
 * Main entry point for Forge UI Reactor Server.
 * Port 9090 chosen to avoid macOS AirPlay Receiver which owns 8080 by default.
 */
fun main() {
    println("Starting Forge UI Reactor Server...")
    ReactorServer.start(9090)
    println("Server running. Press Ctrl+C to stop.")

    // Keep main thread alive
    Thread.currentThread().join()
}