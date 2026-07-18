package borg.trikeshed.forge.window

/**
 * WASI Window Manager.
 * T18 mentions: WasiForgeWindowManager: textual/no-op.
 */
class WasiForgeWindowManager : ForgeWindowManager {
    override fun launch(html: String) {
        println("WasiForgeWindowManager textual/no-op mode. HTML length: \${html.length}")
    }
}
