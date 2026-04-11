package borg.literbike.gates

/**
 * LITERBIKE Gate System (AGPL Licensed)
 * Hierarchical gating for protocols and crypto
 * Ported from literbike/src/gates/mod.rs
 */

/**
 * Master gate trait for LITERBIKE
 */
interface Gate {
    /** Check if gate allows passage */
    suspend fun isOpen(): Boolean

    /** Process data through gate */
    suspend fun process(data: ByteArray): Result<ByteArray>

    /** Gate identifier */
    val name: String

    /** Child gates */
    val children: List<Gate>
}

/**
 * LITEBIKE master gate controller
 */
class LitebikeGateController(
    private val shadowsocksGate: ShadowsocksGate = ShadowsocksGate(),
    private val cryptoGate: CryptoGate = CryptoGate(),
    private val sshGate: SSHGate = SSHGate(),
) {
    private val gates: MutableList<Gate> = mutableListOf(
        shadowsocksGate,
        cryptoGate,
        sshGate,
    )

    /** Route data through appropriate gate */
    suspend fun route(data: ByteArray): Result<ByteArray> {
        for (gate in gates) {
            if (gate.isOpen()) {
                gate.process(data).onSuccess { return Result.success(it) }
            }
        }
        return Result.failure(Exception("No gate could process data"))
    }
}
