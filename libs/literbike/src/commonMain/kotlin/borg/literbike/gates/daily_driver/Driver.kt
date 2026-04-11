package borg.literbike.gates.daily_driver

import borg.literbike.gates.EdgeNetworkGate
import borg.literbike.gates.ExclusiveGateController
import borg.literbike.gates.GateProfile

/**
 * CLI Driver Implementation
 *
 * Entry point for daily driver CLI commands, integrating with gate controller and state.
 * Ported from literbike/src/gates/daily_driver/driver.rs
 */
class CliDriver {
    private val controller: ExclusiveGateController
    private val state: DailyDriverState
    private val switchboard: DselSwitchBoard

    companion object {
        fun new(): CliDriver {
            val controller = ExclusiveGateController.withEdgeGates()
            val state = DailyDriverState(DailyDriverConfig())
            val switchboard = DselSwitchBoard.new()
            return CliDriver(controller, state, switchboard)
        }

        fun withProfile(profile: GateProfile): CliDriver {
            val controller = ExclusiveGateController.withEdgeGates()
            controller.setProfile(profile)

            val config = when (profile) {
                GateProfile.Lite -> DailyDriverConfig.liteMode()
                GateProfile.Standard -> DailyDriverConfig()
                GateProfile.Edge -> DailyDriverConfig.edgeMode()
                GateProfile.Expert -> DailyDriverConfig()
            }

            val state = DailyDriverState(config)
            val switchboard = DselSwitchBoard.newWithProfile(profile)
            return CliDriver(controller, state, switchboard)
        }
    }

    private constructor(
        controller: ExclusiveGateController,
        state: DailyDriverState,
        switchboard: DselSwitchBoard,
    ) {
        this.controller = controller
        this.state = state
        this.switchboard = switchboard
    }

    suspend fun execute(cli: Commands): Result<String> {
        return when (cli) {
            is Commands.Start -> startCommand(cli.bind, cli.port, cli.mode, cli.maxConnections, cli.metrics)
            is Commands.Stop -> stopCommand()
            is Commands.Status -> statusCommand()
            is Commands.Profile -> profileCommand(cli.profile)
            is Commands.Edge -> edgeCommand(cli.maxMemory, cli.maxConnections, cli.compression, cli.crypto)
            is Commands.Switch -> switchCommand(cli.compression, cli.crypto, cli.maxConnections, cli.idleTimeout)
        }
    }

    private suspend fun startCommand(
        bind: String,
        port: Int,
        mode: String,
        maxConnections: Int,
        metrics: Boolean,
    ): Result<String> {
        val driverMode = DriverMode.fromStr(mode)
        val config = DailyDriverConfig(
            mode = driverMode,
            bindAddress = bind,
            port = port,
            maxConnections = maxConnections,
            metricsEnabled = metrics,
        )
        state.updateConfig(config)
        state.setActive(true)

        val profile = controller.getProfile()
        return Result.success("Daily driver started in $mode mode (profile: ${profile.asStr()})")
    }

    private suspend fun stopCommand(): Result<String> {
        state.setActive(false)
        return Result.success("Daily driver stopped")
    }

    private suspend fun statusCommand(): Result<String> {
        val active = state.isActive()
        val config = state.getConfig()
        val profile = controller.getProfile()
        val connections = state.connectionCount()

        val status = buildString {
            appendLine("Daily Driver Status:")
            appendLine("Active: $active")
            appendLine("Mode: ${config.mode}")
            appendLine("Profile: ${profile.asStr()}")
            appendLine("Bind: ${config.bindAddress}:${config.port}")
            append("Connections: $connections/${config.maxConnections}")
        }
        return Result.success(status)
    }

    private suspend fun profileCommand(profile: String): Result<String> {
        val gateProfile = GateProfile.fromStr(profile)
        controller.setProfile(gateProfile)

        val switches = listOf(Switch.Profile(gateProfile))
        for (switch in switches) {
            switchboard.apply(switch)
        }

        return Result.success("Profile set to $profile (${gateProfile.asStr()})")
    }

    private suspend fun edgeCommand(
        maxMemory: Int,
        maxConnections: Int,
        compression: Boolean,
        crypto: Boolean,
    ): Result<String> {
        val runtime = switchboard.getRuntime()
        runtime.maxConnections = maxConnections
        runtime.compressionEnabled = compression
        runtime.cryptoEnabled = crypto

        val config = DailyDriverConfig(
            maxConnections = maxConnections,
        )
        state.updateConfig(config)

        val profile = controller.getProfile()
        val result = buildString {
            appendLine("Edge configuration applied:")
            appendLine("Memory: ${maxMemory / 256} MB")
            appendLine("Connections: $maxConnections")
            appendLine("Compression: $compression")
            append("Crypto: $crypto")
        }
        return Result.success(result)
    }

    private suspend fun switchCommand(
        compression: Boolean?,
        crypto: Boolean?,
        maxConnections: Int?,
        idleTimeout: Long?,
    ): Result<String> {
        val changes = mutableListOf<Switch>()
        compression?.let { changes.add(Switch.Compression(it)) }
        crypto?.let { changes.add(Switch.Crypto(it)) }
        maxConnections?.let { changes.add(Switch.MaxConnections(it)) }
        idleTimeout?.let { changes.add(Switch.IdleTimeout(it)) }

        for (switch in changes) {
            switchboard.apply(switch)
        }

        val runtime = switchboard.getRuntime()
        val result = buildString {
            appendLine("Runtime switches applied:")
            appendLine("Compression: ${runtime.compressionEnabled}")
            appendLine("Crypto: ${runtime.cryptoEnabled}")
            appendLine("Max Connections: ${runtime.maxConnections}")
            append("Idle Timeout: ${runtime.idleTimeoutSecs}s")
        }
        return Result.success(result)
    }
}
