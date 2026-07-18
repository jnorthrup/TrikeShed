package borg.trikeshed.forge.shell.spi

class WasmForgeWindowManager : ForgeWindowManager {
    override fun bind(html: String) {
        // No-op for now due to WasmJs interop complexities, or can add standard interop later
    }

    override fun injectScript(script: String) {
        // No-op
    }

    override fun dispatchEvent(event: String, payload: String) {
        // No-op
    }

    override suspend fun captureSnapshot(): ByteArray {
        return ByteArray(0)
    }
}
