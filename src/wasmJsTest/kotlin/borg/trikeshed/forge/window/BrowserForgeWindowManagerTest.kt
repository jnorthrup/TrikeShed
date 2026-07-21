package borg.trikeshed.forge.window

class BrowserForgeWindowManagerTest : WindowManagerContractTest() {
    override fun getManager(): ForgeWindowManager = BrowserForgeWindowManager()
}
