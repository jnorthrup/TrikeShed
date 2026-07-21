package borg.trikeshed.forge.window

class NativeForgeWindowManagerTest : WindowManagerContractTest() {
    override fun getManager(): ForgeWindowManager = NativeForgeWindowManager()
}
