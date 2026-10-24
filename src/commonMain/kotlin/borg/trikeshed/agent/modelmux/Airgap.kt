package borg.trikeshed.agent.modelmux

import borg.trikeshed.userspace.nio.platform.spi.SystemOperations
import kotlinx.coroutines.currentCoroutineContext
import kotlin.coroutines.CoroutineContext

/** Model egress admission. Local Kotlin inference and retained evidence remain available. */
class Airgap(val enabled: Boolean = true) : CoroutineContext.Element {
    companion object Key : CoroutineContext.Key<Airgap> {
        const val PROPERTY = "trikeshed.airgap"
        const val ENVIRONMENT = "TRIKESHED_AIRGAP"

        /** Either switch can prohibit egress; malformed or empty switches fail closed. */
        fun configured(
            property: String? = SystemOperations.default.getProperty(PROPERTY),
            environment: String? = SystemOperations.default.getenv(ENVIRONMENT),
        ): Boolean = enabled(property) || enabled(environment)

        private fun enabled(value: String?): Boolean = when (value?.trim()?.lowercase()) {
            null, "false", "0", "no", "off" -> false
            else -> true
        }

        /** A child context cannot override a process-wide prohibition. */
        suspend fun active(): Boolean = configured() || currentCoroutineContext()[Key]?.enabled == true

        suspend fun requireRemote() {
            if (active()) throw Rejection()
        }
    }

    override val key: CoroutineContext.Key<Airgap> get() = Key

    class Rejection : IllegalStateException("airgap: remote model calls are disabled")
}
