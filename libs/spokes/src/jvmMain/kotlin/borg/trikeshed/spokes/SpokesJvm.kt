package borg.trikeshed.spokes

import kotlinx.coroutines.Job

/** JVM-specific default for SpokesElement.Standalone */
object SpokesJvm {
    @JvmStatic
    @JvmOverloads
    fun standalone(parentJob: Job? = null): SpokesElement {
        val cacheDir = System.getProperty("user.home") + "/.m2/spokes-cache"
        return SpokesElement.Standalone(cacheDir, parentJob)
    }
}
