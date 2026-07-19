package borg.trikeshed.forge.shell.spi

class LinuxForgeWindowManager : ForgeWindowManager {
    override fun bind(html: String) {
        // No-op
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
