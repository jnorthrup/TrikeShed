package borg.trikeshed.forge.window

class JvmForgeWindowManagerTest : WindowManagerContractTest() {
    override fun getManager(): ForgeWindowManager = JvmForgeWindowManager()
}
