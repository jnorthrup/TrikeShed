package borg.trikeshed.forge.shell.spi

class JvmForgeWindowManager : ForgeWindowManager {
    override fun bind(html: String) {
        // No-op for now
    }

    override fun injectScript(script: String) {
        // No-op for now
    }

    override fun dispatchEvent(event: String, payload: String) {
        // No-op for now
    }

    override suspend fun captureSnapshot(): ByteArray {
        return ByteArray(0)
    }
}
