package borg.trikeshed.userspace.nio

// Test-only expect declarations for platform socket adapters
expect fun createListeningSocket(host: String = "127.0.0.1", port: Int = 0): ListeningSocket
expect fun createConnectedSocket(host: String = "127.0.0.1", port: Int): ConnectedSocket
