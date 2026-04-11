package borg.literbike.gates.daily_driver

import kotlinx.serialization.Serializable

/**
 * Daily Driver CLI
 *
 * Command-line interface for Litebike daily driver operations.
 * Ported from literbike/src/gates/daily_driver/cli.rs
 */

/** Represents CLI commands parsed from arguments */
sealed class Commands {
    data class Start(
        val bind: String = "127.0.0.1",
        val port: Int = 8080,
        val mode: String = "proxy",
        val maxConnections: Int = 25,
        val metrics: Boolean = false,
    ) : Commands()

    data object Stop : Commands()
    data object Status : Commands()

    data class Profile(val profile: String) : Commands()

    data class Edge(
        val maxMemory: Int = 256,
        val maxConnections: Int = 50,
        val compression: Boolean = true,
        val crypto: Boolean = true,
    ) : Commands()

    data class Switch(
        val compression: Boolean? = null,
        val crypto: Boolean? = null,
        val maxConnections: Int? = null,
        val idleTimeout: Long? = null,
    ) : Commands()
}

@Serializable
data class DriverStatus(
    val active: Boolean = false,
    val mode: String = "proxy",
    val bindAddress: String = "127.0.0.1",
    val port: Int = 8080,
    val connections: Int = 0,
    val maxConnections: Int = 25,
    val profile: String = "lite",
)

/**
 * Runtime switch configuration for dynamic updates
 */
@Serializable
data class RuntimeSwitches(
    var compressionEnabled: Boolean = true,
    var cryptoEnabled: Boolean = true,
    var maxConnections: Int = 25,
    var idleTimeoutSecs: Long = 300L,
    var metricsEnabled: Boolean = false,
) {
    fun applyFromCli(switches: Switches) {
        switches.compression?.let { compressionEnabled = it }
        switches.crypto?.let { cryptoEnabled = it }
        switches.maxConnections?.let { maxConnections = it }
        switches.idleTimeout?.let { idleTimeoutSecs = it }
    }
}

class Switches(
    var compression: Boolean? = null,
    var crypto: Boolean? = null,
    var maxConnections: Int? = null,
    var idleTimeout: Long? = null,
) {
    companion object {
        fun new(): Switches = Switches()
    }

    fun withCompression(enabled: Boolean): Switches {
        compression = enabled
        return this
    }

    fun withCrypto(enabled: Boolean): Switches {
        crypto = enabled
        return this
    }

    fun withMaxConnections(max: Int): Switches {
        maxConnections = max
        return this
    }

    fun withIdleTimeout(secs: Long): Switches {
        idleTimeout = secs
        return this
    }
}
