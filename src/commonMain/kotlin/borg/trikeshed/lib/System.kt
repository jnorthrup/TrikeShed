package borg.trikeshed.lib

import kotlin.time.Clock

@Deprecated("Use SystemOperations CCEK: coroutineContext[SystemOperations.Key]")
expect object System {
    fun getenv(name: CharSequence, defaultVal: CharSequence? = null
    ): CharSequence?
    val homedir: CharSequence
}

public fun System.currentTimeMillis(): Long= Clock.System.now().toEpochMilliseconds()
public fun System.getProperty(string: CharSequence,defVal: CharSequence?=null) = System.getenv(string,defVal)
