package borg.trikeshed.lib

import borg.trikeshed.userspace.nio.platform.spi.SystemOperations
import kotlin.time.Clock

object System {
    private val spi: SystemOperations by lazy {
        loadPlatformSystemOperations()
    }

    fun getenv(name: String, defaultVal: String? = null): String? =
        spi.getenv(name, defaultVal)

    val homedir: String
        get() = spi.homedir
}

internal expect fun loadPlatformSystemOperations(): SystemOperations

public fun System.currentTimeMillis(): Long = Clock.System.now().toEpochMilliseconds()
public fun System.getProperty(string: String, defVal: String? = null) = System.getenv(string, defVal)
