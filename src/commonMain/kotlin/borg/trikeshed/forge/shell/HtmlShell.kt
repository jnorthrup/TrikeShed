package borg.trikeshed.forge.shell

expect object HtmlShell {
    fun load(): String
    fun cssAsset(name: String): String
    fun jsAsset(name: String): String
}
