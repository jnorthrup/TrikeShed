package borg.trikeshed.forge.gallery

import kotlin.jvm.JvmStatic

/** Compatibility entrypoint for callers using the former gallery package. */
object ForgeComposeFactory {
    @JvmStatic
    fun main(args: Array<String>) {
        borg.trikeshed.forge.shell.main()
    }

    @JvmStatic
    fun createAndShow() {
        borg.trikeshed.forge.shell.main()
    }
}
