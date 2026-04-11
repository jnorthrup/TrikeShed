package borg.literbike.gates.daily_driver

import borg.literbike.gates.GateProfile

/**
 * DSEL Switches for Runtime Profile Control
 *
 * Domain-specific expression language for dynamic configuration
 * and runtime switching of Litebike profiles.
 * Ported from literbike/src/gates/daily_driver/switches.rs
 */

import kotlinx.serialization.Serializable

@Serializable
data class DselSwitches(
    var currentProfile: GateProfile = GateProfile.Lite,
    var runtime: RuntimeSwitches = RuntimeSwitches(),
    var driverMode: DriverMode = DriverMode.Proxy,
) {
    companion object {
        fun new(profile: GateProfile): DselSwitches {
            val runtime = when (profile) {
                GateProfile.Lite -> RuntimeSwitches(
                    compressionEnabled = true,
                    cryptoEnabled = false,
                    maxConnections = 10,
                    idleTimeoutSecs = 60L,
                    metricsEnabled = false,
                )
                GateProfile.Standard -> RuntimeSwitches()
                GateProfile.Edge -> RuntimeSwitches(
                    compressionEnabled = true,
                    cryptoEnabled = true,
                    maxConnections = 50,
                    idleTimeoutSecs = 300L,
                    metricsEnabled = true,
                )
                GateProfile.Expert -> RuntimeSwitches(
                    compressionEnabled = false,
                    cryptoEnabled = true,
                    maxConnections = 100,
                    idleTimeoutSecs = 600L,
                    metricsEnabled = true,
                )
            }
            return DselSwitches(
                currentProfile = profile,
                runtime = runtime,
                driverMode = DriverMode.Proxy,
            )
        }
    }

    fun switchTo(profile: GateProfile) {
        val newRuntime = when (profile) {
            GateProfile.Lite -> RuntimeSwitches(
                compressionEnabled = true,
                cryptoEnabled = false,
                maxConnections = 10,
                idleTimeoutSecs = 60L,
                metricsEnabled = false,
            )
            GateProfile.Standard -> RuntimeSwitches()
            GateProfile.Edge -> RuntimeSwitches(
                compressionEnabled = true,
                cryptoEnabled = true,
                maxConnections = 50,
                idleTimeoutSecs = 300L,
                metricsEnabled = true,
            )
            GateProfile.Expert -> RuntimeSwitches(
                compressionEnabled = false,
                cryptoEnabled = true,
                maxConnections = 100,
                idleTimeoutSecs = 600L,
                metricsEnabled = true,
            )
        }
        currentProfile = profile
        runtime = newRuntime
    }

    fun applySwitch(switch: Switch) {
        when (switch) {
            is Switch.Compression -> runtime.compressionEnabled = switch.enabled
            is Switch.Crypto -> runtime.cryptoEnabled = switch.enabled
            is Switch.MaxConnections -> runtime.maxConnections = switch.max
            is Switch.IdleTimeout -> runtime.idleTimeoutSecs = switch.secs
            is Switch.Metrics -> runtime.metricsEnabled = switch.enabled
            is Switch.Profile -> switchTo(switch.profile)
            is Switch.DriverMode -> driverMode = switch.mode
        }
    }
}

@Serializable
sealed class Switch {
    data class Compression(val enabled: Boolean) : Switch()
    data class Crypto(val enabled: Boolean) : Switch()
    data class MaxConnections(val max: Int) : Switch()
    data class IdleTimeout(val secs: Long) : Switch()
    data class Metrics(val enabled: Boolean) : Switch()
    data class Profile(val profile: GateProfile) : Switch()
    data class DriverMode(val mode: DriverMode) : Switch()
}

class DselSwitchBoard(
    profile: GateProfile = GateProfile.Lite,
) {
    private var switches: DselSwitches = DselSwitches.new(profile)
    private val history: MutableList<Switch> = mutableListOf()

    companion object {
        fun new(): DselSwitchBoard = DselSwitchBoard(GateProfile.Lite)
        fun newWithProfile(profile: GateProfile): DselSwitchBoard = DselSwitchBoard(profile)
    }

    fun apply(switch: Switch) {
        switches.applySwitch(switch)
        history.add(switch)
    }

    fun getState(): DselSwitches = switches
    fun getProfile(): GateProfile = switches.currentProfile
    fun getRuntime(): RuntimeSwitches = switches.runtime
    fun history(): List<Switch> = history.toList()
    fun clearHistory() { history.clear() }
}

/**
 * Parse DSEL expression into switches
 */
fun parseDsel(expr: String): Result<List<Switch>> {
    val switches = mutableListOf<Switch>()
    val trimmed = expr.trim()

    for (part in trimmed.split(';')) {
        val trimmedPart = part.trim()
        if (trimmedPart.isEmpty()) continue

        val parts = trimmedPart.split('=', limit = 2)
        if (parts.size != 2) {
            return Result.failure(Exception("Invalid switch: $trimmedPart"))
        }

        val key = parts[0].trim().lowercase()
        val value = parts[1].trim()

        when (key) {
            "profile" -> switches.add(Switch.Profile(GateProfile.fromStr(value)))
            "compression", "compress" -> switches.add(
                Switch.Compression(value == "true" || value == "1" || value == "on")
            )
            "crypto", "crypt" -> switches.add(
                Switch.Crypto(value == "true" || value == "1" || value == "on")
            )
            "max_conn", "max_connections" -> {
                val max = value.toIntOrNull()
                    ?: return Result.failure(Exception("Invalid number: $value"))
                switches.add(Switch.MaxConnections(max))
            }
            "idle", "idle_timeout" -> {
                val secs = value.toLongOrNull()
                    ?: return Result.failure(Exception("Invalid number: $value"))
                switches.add(Switch.IdleTimeout(secs))
            }
            "metrics" -> switches.add(
                Switch.Metrics(value == "true" || value == "1" || value == "on")
            )
            "mode" -> switches.add(Switch.DriverMode(DriverMode.fromStr(value)))
            else -> return Result.failure(Exception("Unknown switch: $key"))
        }
    }

    return Result.success(switches)
}
