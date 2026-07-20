package borg.trikeshed.forge.shell

actual object HtmlShell {
<<<<<<< HEAD
    actual fun load(): String {
        throw UnsupportedOperationException("Synchronous fetch not supported in JS. Use async.")
    }

    actual fun cssAsset(name: String): String {
        throw UnsupportedOperationException("Synchronous fetch not supported in JS. Use async.")
    }

    actual fun jsAsset(name: String): String {
        throw UnsupportedOperationException("Synchronous fetch not supported in JS. Use async.")
    }
=======
    actual fun load(): String = TODO("Implement fetch via kotlinx.browser.window.fetch")
    actual fun cssAsset(name: String): String = TODO("Implement fetch via kotlinx.browser.window.fetch")
    actual fun jsAsset(name: String): String = TODO("Implement fetch via kotlinx.browser.window.fetch")
>>>>>>> origin/add-html-shell-assets-10555369453043646034
}
