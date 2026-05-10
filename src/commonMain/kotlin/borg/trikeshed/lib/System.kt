package borg.trikeshed.lib

import kotlin.time.Clock

@Deprecated("Use SystemOperations CCEK: coroutineContext[SystemOperations.Key]")
expect object System {
    fun getenv(name: String, defaultVal: String? = null
    ): String?
    val homedir: String
}

public fun System.currentTimeMillis(): Long= Clock.System.now().toEpochMilliseconds()
public fun System.getProperty(string: String,defVal: String?=null) = System.getenv(string,defVal)
