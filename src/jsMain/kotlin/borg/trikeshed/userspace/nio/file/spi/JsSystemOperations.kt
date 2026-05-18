package borg.trikeshed.userspace.nio.file.spi

import borg.trikeshed.lib.jsHomeDir
import borg.trikeshed.lib.processObj
import borg.trikeshed.userspace.nio.platform.spi.SystemOperations

class JsSystemOperations : SystemOperations {

    override fun getenv(name: String, defaultVal: String?): String? =
        (processObj.env[name] as? String) ?: defaultVal

    override fun getProperty(name: String, defaultVal: String?): String? = defaultVal

    override val homedir: String
        get() = jsHomeDir()
}
