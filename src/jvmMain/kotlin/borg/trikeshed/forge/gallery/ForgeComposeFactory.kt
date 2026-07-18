package borg.trikeshed.forge.gallery

import borg.trikeshed.forge.shell.main
import kotlin.jvm.JvmStatic

/**
 * Factory for creating the Compose Desktop window.
 * Lives in jvmMain so the JS/WASM targets don't pull in Compose dependencies.
 * The actual Forge workspace shell is in commonMain (ForgeWorkspace).
 */
object ForgeComposeFactory {

    /**
     * Creates and shows the Forge JVM window.
     */
    @JvmStatic
    fun main(args: Array<String>) {
        borg.trikeshed.forge.shell.main()
    }
}
