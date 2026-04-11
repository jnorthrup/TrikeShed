package borg.literbike.gates.daily_driver

import kotlinx.serialization.Serializable

/**
 * Daily Driver - Litebike as Daily Driver
 *
 * Provides a streamlined, reduced-footprint integration of Litebike
 * optimized for everyday use as a daily driver with minimal resource usage.
 * Ported from literbike/src/gates/daily_driver.rs
 */

@Serializable
enum class DriverMode {
    Proxy,
    Gateway,
    Tunnel,
    Monitor;

    companion object {
        fun default(): DriverMode = Proxy

        fun fromStr(s: String): DriverMode = when (s.lowercase()) {
            "proxy" -> Proxy
            "gateway", "gw" -> Gateway
            "tunnel", "tun" -> Tunnel
            "monitor", "mon" -> Monitor
            else -> Proxy
        }
    }
}

/** Daily driver configuration */
@Serializable
data class DailyDriverConfig(
    val mode: DriverMode = DriverMode.Proxy,
    val bindAddress: String = "127.0.0.1",
    val port: Int = 8080,
    val maxConnections: Int = 25,
    val bufferSize: Int = 4096,
    val idleTimeoutSecs: Long = 300L,
    val metricsEnabled: Boolean = false,
) {
    companion object {
        fun liteMode(): DailyDriverConfig = DailyDriverConfig(
            mode = DriverMode.Proxy,
            bindAddress = "127.0.0.1",
            port = 8080,
            maxConnections = 10,
            bufferSize = 2048,
            idleTimeoutSecs = 60L,
            metricsEnabled = false,
        )

        fun edgeMode(): DailyDriverConfig = DailyDriverConfig(
            mode = DriverMode.Gateway,
            bindAddress = "0.0.0.0",
            port = 8080,
            maxConnections = 50,
            bufferSize = 4096,
            idleTimeoutSecs = 300L,
            metricsEnabled = true,
        )
    }
}

/** Daily driver state */
class DailyDriverState(
    config: DailyDriverConfig = DailyDriverConfig(),
) {
    private var config: DailyDriverConfig = config
    private var active: Boolean = false
    private var connections: Int = 0

    companion object {
        fun default(): DailyDriverState = DailyDriverState(DailyDriverConfig())
    }

    fun isActive(): Boolean = active
    fun setActive(active: Boolean) { this.active = active }

    fun incrementConnections() { connections++ }
    fun decrementConnections() { connections = (connections - 1).coerceAtLeast(0) }
    fun connectionCount(): Int = connections
    fun getConfig(): DailyDriverConfig = config
    fun updateConfig(config: DailyDriverConfig) { this.config = config }
}

/** Memory-efficient connection tracker */
class ConnectionTracker(
    private val maxConnections: Int,
) {
    private var active: Int = 0

    fun tryAcquire(): Boolean {
        return if (active < maxConnections) {
            active++
            true
        } else {
            false
        }
    }

    fun release() {
        active = (active - 1).coerceAtLeast(0)
    }

    fun activeCount(): Int = active
    fun available(): Int = (maxConnections - active).coerceAtLeast(0)
}
