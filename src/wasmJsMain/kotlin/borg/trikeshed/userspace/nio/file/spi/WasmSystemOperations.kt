package borg.trikeshed.userspace.nio.file.spi

import borg.trikeshed.System

class WasmSystemOperations : SystemOperations {

    override fun getenv(name: String, defaultVal: String?) = System.getenv(name, defaultVal)
    override val homedir: String get() = System.homedir
}
