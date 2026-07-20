package borg.trikeshed.forge.shell

actual object HtmlShell {
    actual fun load(): String {
        return HtmlShell::class.java.getResourceAsStream("/shell/index.html")
            ?.bufferedReader()?.readText()
            ?: throw IllegalArgumentException("no html asset: index.html")
    }

    actual fun cssAsset(name: String): String {
        return HtmlShell::class.java.getResourceAsStream("/shell/$name.css")
            ?.bufferedReader()?.readText()
            ?: throw IllegalArgumentException("no css asset: $name")
    }

    actual fun jsAsset(name: String): String {
        return HtmlShell::class.java.getResourceAsStream("/shell/$name.js")
            ?.bufferedReader()?.readText()
            ?: throw IllegalArgumentException("no js asset: $name")
    }
}
