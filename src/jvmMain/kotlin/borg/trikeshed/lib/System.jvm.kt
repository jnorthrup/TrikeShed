package borg.trikeshed.lib

actual object System {
    actual fun getenv(name: CharSequence, defaultVal: CharSequence?): CharSequence? = java.lang.System.getenv(name.toString())?:defaultVal

    actual val homedir: CharSequence
        get() = java.lang.System.getProperty("user.home")
            ?: error("Failed to determine home directory")


}
