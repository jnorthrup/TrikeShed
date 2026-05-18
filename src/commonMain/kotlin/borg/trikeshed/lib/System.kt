package borg.trikeshed.lib

import borg.trikeshed.userspace.nio.platform.spi.SystemOperations
import kotlin.time.Clock

@Deprecated("Use SystemOperations: coroutineContext[SystemOperations.Key] or SystemOperations.default")
object System {
    fun getenv(name: String, defaultVal: String? = null): String? =
        SystemOperations.default.getenv(name, defaultVal)

    val homedir: String
        get() = SystemOperations.default.homedir
}

public fun System.currentTimeMillis(): Long = Clock.System.now().toEpochMilliseconds()
public fun System.getProperty(string: String, defVal: String? = null) = System.getenv(string, defVal)
