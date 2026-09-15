package borg.trikeshed.forge.shell

data class ShellConfig(
    val version: String = "1.0.0",
    val title: String = "TrikeShed Forge",
    val scriptBundleName: String = "kotlin/forge/forge.js",
    val cssBundles: List<String> = listOf("app.css"),
    val jsBundles: List<String> = listOf(scriptBundleName),
) {
    init { require(scriptBundleName.isNotBlank()) { "scriptBundleName blank" } }
    init { require(title.isNotBlank()) { "title blank" } }
}
