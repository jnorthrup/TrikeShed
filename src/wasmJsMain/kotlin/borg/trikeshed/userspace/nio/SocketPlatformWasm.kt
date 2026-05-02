package borg.trikeshed.userspace.nio

actual fun createListeningSocket(host: String, port: Int): ListeningSocket =
    commonCreateListeningSocket(host, port)

actual fun createConnectedSocket(host: String, port: Int): ConnectedSocket =
    commonCreateConnectedSocket(host, port)
