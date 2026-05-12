package borg.trikeshed.userspace.nio.file.spi

class JvmSystemOperations : SystemOperations {

    override fun getenv(name: CharSequence, defaultVal: CharSequence?): CharSequence? =
        java.lang.System.getenv(name.toString()) ?: defaultVal

    override val homedir: CharSequence
        get() = java.lang.System.getProperty("user.home") ?: "/"
}
