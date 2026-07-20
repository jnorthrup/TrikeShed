package borg.trikeshed.forge.shell

class ShellAssetRegistry(private val config: ShellConfig) {
    fun requiredAssets(): List<String> = listOf("index.html") + config.cssBundles + config.jsBundles
    fun scriptTagFor(bundle: String): String = "<script src=\"./$bundle\"></script>"
}
