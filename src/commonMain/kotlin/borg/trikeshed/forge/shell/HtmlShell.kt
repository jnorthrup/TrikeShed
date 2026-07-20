package borg.trikeshed.forge.shell

expect object HtmlShell {
    fun load(): String                       // returns the contents of shell/index.html
    fun cssAsset(name: String): String       // returns contents of shell/{name}.css
    fun jsAsset(name: String): String        // returns contents of shell/{name}.js
}
