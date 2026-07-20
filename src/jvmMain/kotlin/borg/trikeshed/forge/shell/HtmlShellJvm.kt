package borg.trikeshed.forge.shell

actual object HtmlShell {
    actual fun load(): String =
        HtmlShell::class.java.getResourceAsStream("/shell/index.html")?.bufferedReader()?.use { it.readText() } ?: ""

    actual fun cssAsset(name: String): String =
        HtmlShell::class.java.getResourceAsStream("/shell/$name.css")?.bufferedReader()?.use { it.readText() } ?: throw IllegalArgumentException("no css asset: $name")

    actual fun jsAsset(name: String): String =
        HtmlShell::class.java.getResourceAsStream("/shell/$name.js")?.bufferedReader()?.use { it.readText() } ?: throw IllegalArgumentException("no js asset: $name")
}
