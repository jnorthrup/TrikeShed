package borg.trikeshed.userspace.nio.file.spi

import borg.trikeshed.System

class JsSystemOperations : SystemOperations {

    override fun getenv(name: String, defaultVal: String?) = System.getenv(name, defaultVal)
    override val homedir: String get() = System.homedir
}
