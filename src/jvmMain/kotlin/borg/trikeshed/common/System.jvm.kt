package borg.trikeshed.common

actual object System {
    actual fun getenv(name: String, string: String): String? = java.lang.System.getenv(name)

    actual val homedir: String
        get() = java.lang.System.getProperty("user.home")
            ?: error("Failed to determine home directory")
}
