package borg.trikeshed.userspace.nio.file.spi

class JvmSystemOperations : SystemOperations {

    override fun getenv(name: String, defaultVal: String?): String? =
        java.lang.System.getenv(name) ?: defaultVal

    override val homedir: String
        get() = java.lang.System.getProperty("user.home") ?: "/"
}
