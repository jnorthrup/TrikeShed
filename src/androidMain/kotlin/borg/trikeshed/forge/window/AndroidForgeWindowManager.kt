package borg.trikeshed.forge.window

/**
 * Android Window Manager.
 * T18 mentions: AndroidForgeWindowManager: WebView wrapper.
 */
class AndroidForgeWindowManager : ForgeWindowManager {
    override fun launch(html: String) {
        println("AndroidForgeWindowManager: render HTML via WebView wrapper.")
    }
}
