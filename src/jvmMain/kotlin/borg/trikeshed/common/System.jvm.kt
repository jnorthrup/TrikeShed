package borg.trikeshed.common

actual object System {
    actual fun getenv(name: String, defaultVal: String?): String? = java.lang.System.getenv(name)?:defaultVal

    actual val homedir: String
        get() = java.lang.System.getProperty("user.home")
            ?: error("Failed to determine home directory")


}
