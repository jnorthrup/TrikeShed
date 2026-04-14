package borg.trikeshed.common

import kotlin.time.Clock

expect object System {
    fun getenv(name: String, defaultVal: String? = null
    ): String?
    val homedir: String
}

public fun System.currentTimeMillis(): Long= Clock.System.now().toEpochMilliseconds()
