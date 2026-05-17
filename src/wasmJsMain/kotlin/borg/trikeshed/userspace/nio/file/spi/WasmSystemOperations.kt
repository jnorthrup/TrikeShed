package borg.trikeshed.userspace.nio.file.spi

import borg.trikeshed.lib.System
import borg.trikeshed.userspace.nio.platform.spi.SystemOperations

class WasmSystemOperations : SystemOperations {

    override fun getenv(name: String, defaultVal: String?) = System.getenv(name, defaultVal)
    override fun getProperty(name: String, defaultVal: String?) = defaultVal
    override val homedir: String get() = System.homedir
}
