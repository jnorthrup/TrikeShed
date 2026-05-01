package borg.trikeshed.userspace.nio

// Test-only expect declarations for platform socket adapters
expect fun createListeningSocket(host: String, port: Int): ListeningSocket
expect fun createConnectedSocket(host: String, port: Int): ConnectedSocket
