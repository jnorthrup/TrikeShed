package borg.literbike.gates

/**
 * Exclusive Gate System with Edge Profile Support
 *
 * Provides hierarchical gating for Litebike with profile-based
 * down-integration for edge computing scenarios.
 * Ported from literbike/src/gates/exclusive.rs
 */
import kotlinx.serialization.Serializable

@Serializable
enum class GateProfile {
    Lite,
    Standard,
    Edge,
    Expert;

    companion object {
        fun default(): GateProfile = Lite

        fun fromStr(s: String): GateProfile = when (s.lowercase()) {
            "lite" -> Lite
            "standard", "std" -> Standard
            "edge" -> Edge
            "expert" -> Expert
            else -> Lite
        }
    }

    fun asStr(): String = when (this) {
        Lite -> "lite"
        Standard -> "standard"
        Edge -> "edge"
        Expert -> "expert"
    }
}

/**
 * Exclusive gate that can be in only one state at a time
 */
interface ExclusiveGate {
    suspend fun isOpen(): Boolean
    suspend fun open(): Result<Unit>
    suspend fun close(): Result<Unit>
    val name: String
    val profile: GateProfile
}

/**
 * Gate controller with profile-aware routing
 */
class ExclusiveGateController {
    private val gates: MutableList<ExclusiveGate> = mutableListOf()
    private var currentProfile: GateProfile = GateProfile.default()
    private var edgeMode: Boolean = false

    companion object {
        fun withEdgeGates(): ExclusiveGateController {
            val controller = ExclusiveGateController()
            controller.registerGate(EdgeCryptoGate())
            controller.registerGate(EdgeNetworkGate())
            return controller
        }

        fun withAllGates(): ExclusiveGateController = withEdgeGates()
    }

    fun registerGate(gate: ExclusiveGate) {
        gates.add(gate)
    }

    fun setProfile(profile: GateProfile) {
        currentProfile = profile
        edgeMode = profile == GateProfile.Edge || profile == GateProfile.Expert
    }

    fun getProfile(): GateProfile = currentProfile
    fun isEdgeMode(): Boolean = edgeMode

    suspend fun routeThroughGates(data: ByteArray): Result<ByteArray> {
        val profile = getProfile()
        for (gate in gates) {
            if (gate.profile.ordinal <= profile.ordinal && gate.isOpen()) {
                // Use the gate's process method if it implements the Gate interface
                when (gate) {
                    is EdgeCryptoGate -> {
                        // EdgeCryptoGate doesn't implement Gate directly, use its own process
                    }
                    is EdgeNetworkGate -> {
                        // Handled below via the process function
                    }
                }
                // For simplicity, we use the process function extension
                val result = processGate(gate, data)
                if (result.isSuccess) return result
            }
        }
        return Result.failure(Exception("No gate could process data"))
    }

    suspend fun openGate(name: String): Result<Unit> {
        val gate = gates.find { it.name == name }
            ?: return Result.failure(Exception("Gate '$name' not found"))
        return gate.open()
    }

    suspend fun closeGate(name: String): Result<Unit> {
        val gate = gates.find { it.name == name }
            ?: return Result.failure(Exception("Gate '$name' not found"))
        return gate.close()
    }
}

/**
 * Edge-optimized crypto gate with minimal overhead
 */
class EdgeCryptoGate(
    enabled: Boolean = false,
) : ExclusiveGate {
    private var enabled: Boolean = enabled
    override val profile: GateProfile = GateProfile.Edge

    override suspend fun isOpen(): Boolean = enabled
    override suspend fun open(): Result<Unit> { enabled = true; return Result.success(Unit) }
    override suspend fun close(): Result<Unit> { enabled = false; return Result.success(Unit) }
    override val name: String get() = "edge_crypto"
}

/**
 * Edge-optimized network gate
 */
class EdgeNetworkGate(
    enabled: Boolean = false,
) : ExclusiveGate {
    private var enabled: Boolean = enabled
    var compressionEnabled: Boolean = true
    override val profile: GateProfile = GateProfile.Edge

    override suspend fun isOpen(): Boolean = enabled
    override suspend fun open(): Result<Unit> { enabled = true; return Result.success(Unit) }
    override suspend fun close(): Result<Unit> { enabled = false; return Result.success(Unit) }
    override val name: String get() = "edge_network"

    suspend fun process(data: ByteArray): Result<ByteArray> {
        if (!isOpen()) {
            return Result.failure(Exception("Gate closed"))
        }
        return if (compressionEnabled) {
            // Simple length-prefixed compression placeholder
            val result = ByteArray(data.size + 4)
            val len = data.size.toUInt()
            result[0] = (len and 0xFFu).toByte()
            result[1] = ((len shr 8) and 0xFFu).toByte()
            result[2] = ((len shr 16) and 0xFFu).toByte()
            result[3] = ((len shr 24) and 0xFFu).toByte()
            data.copyInto(result, 4)
            Result.success(result)
        } else {
            Result.success(data.copyOf())
        }
    }
}

/** Extension function to process data through an ExclusiveGate */
suspend fun processGate(gate: ExclusiveGate, data: ByteArray): Result<ByteArray> {
    return when (gate) {
        is EdgeNetworkGate -> gate.process(data)
        else -> Result.success(data.copyOf())
    }
}

/**
 * Edge profile configuration
 */
@Serializable
data class EdgeProfileConfig(
    val maxMemoryMb: Int = 256,
    val maxConnections: Int = 50,
    val compressionEnabled: Boolean = true,
    val cryptoEnabled: Boolean = true,
    val batchSize: Int = 64,
) {
    companion object {
        fun fromProfile(profile: GateProfile): EdgeProfileConfig = when (profile) {
            GateProfile.Lite -> EdgeProfileConfig(
                maxMemoryMb = 128,
                maxConnections = 10,
                compressionEnabled = true,
                cryptoEnabled = false,
                batchSize = 32,
            )
            GateProfile.Standard -> EdgeProfileConfig()
            GateProfile.Edge -> EdgeProfileConfig(
                maxMemoryMb = 256,
                maxConnections = 50,
                compressionEnabled = true,
                cryptoEnabled = true,
                batchSize = 64,
            )
            GateProfile.Expert -> EdgeProfileConfig(
                maxMemoryMb = 512,
                maxConnections = 100,
                compressionEnabled = false,
                cryptoEnabled = true,
                batchSize = 128,
            )
        }
    }
}
