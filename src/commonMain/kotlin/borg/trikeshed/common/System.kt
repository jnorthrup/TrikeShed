package borg.trikeshed.common

expect object System {
    fun getenv(name: String, default: String? = null
    ): String?
    val homedir: String
}

