package borg.trikeshed.common

expect object System {
    fun getenv(name: String): String?
    val homedir: String
}

