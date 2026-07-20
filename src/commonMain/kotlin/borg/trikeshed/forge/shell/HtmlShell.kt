package borg.trikeshed.forge.shell

expect object HtmlShell {
<<<<<<< HEAD
    fun load(): String                       // returns the contents of shell/index.html
    fun cssAsset(name: String): String       // returns contents of shell/{name}.css
    fun jsAsset(name: String): String        // returns contents of shell/{name}.js
=======
    fun load(): String
    fun cssAsset(name: String): String
    fun jsAsset(name: String): String
>>>>>>> origin/add-html-shell-assets-10555369453043646034
}
