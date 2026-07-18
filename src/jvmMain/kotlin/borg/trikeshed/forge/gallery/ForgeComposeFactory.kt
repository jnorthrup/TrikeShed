package borg.trikeshed.forge.gallery

import borg.trikeshed.forge.shell.main
import kotlin.jvm.JvmStatic

/** Compatibility entrypoint for callers using the former gallery package. */
object ForgeComposeFactory {

    /**
     * Creates and shows the Forge JVM window.
     */
    @JvmStatic
    fun main(args: Array<String>) {
        borg.trikeshed.forge.shell.main()
    }
}
