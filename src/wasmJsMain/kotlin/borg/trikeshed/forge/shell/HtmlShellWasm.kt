package borg.trikeshed.forge.shell

actual object HtmlShell {
    actual fun load(): String {
        throw UnsupportedOperationException("Synchronous fetch not supported in JS. Use async.")
    }

    actual fun cssAsset(name: String): String {
        throw UnsupportedOperationException("Synchronous fetch not supported in JS. Use async.")
    }

    actual fun jsAsset(name: String): String {
        throw UnsupportedOperationException("Synchronous fetch not supported in JS. Use async.")
    }
}
