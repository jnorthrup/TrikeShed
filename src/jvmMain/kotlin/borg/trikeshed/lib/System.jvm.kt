package borg.trikeshed.lib

actual object System {
    actual val homedir: String
        get() = java.lang.System.getProperty("user.home")

    actual fun getenv(name: String, defaultVal: String?): String? =
        java.lang.System.getenv(name) ?: defaultVal
}
