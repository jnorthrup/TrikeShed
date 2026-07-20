package borg.trikeshed.forge.shell

actual object HtmlShell {
    actual fun load(): String = TODO("Implement fetch via kotlinx.browser.window.fetch")
    actual fun cssAsset(name: String): String = TODO("Implement fetch via kotlinx.browser.window.fetch")
    actual fun jsAsset(name: String): String = TODO("Implement fetch via kotlinx.browser.window.fetch")
}
