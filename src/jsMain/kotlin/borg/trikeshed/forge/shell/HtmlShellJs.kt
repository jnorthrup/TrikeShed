package borg.trikeshed.forge.shell

import borg.trikeshed.platform.discontinued

actual object HtmlShell {
    actual fun load(): String {
        discontinued("shell.load")
    }

    actual fun cssAsset(name: String): String {
        discontinued("shell.load")
    }

    actual fun jsAsset(name: String): String {
        discontinued("shell.load")
    }
}
