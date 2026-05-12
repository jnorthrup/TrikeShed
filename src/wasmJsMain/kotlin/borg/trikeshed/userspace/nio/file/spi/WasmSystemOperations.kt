package borg.trikeshed.userspace.nio.file.spi

import borg.trikeshed.lib.System

class WasmSystemOperations : SystemOperations {

    override fun getenv(name: CharSequence, defaultVal: CharSequence?) = System.getenv(name, defaultVal)
    override val homedir: CharSequence get() = System.homedir
}
