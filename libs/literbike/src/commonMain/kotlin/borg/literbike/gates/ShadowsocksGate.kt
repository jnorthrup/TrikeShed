package borg.literbike.gates

import kotlin.math.log2

/**
 * Shadowsocks Gate for LITEBIKE
 * Gates Shadowsocks protocol behind LITEBIKE
 * Ported from literbike/src/gates/shadowsocks_gate.rs
 */
class ShadowsocksGate {
    private var enabled: Boolean = false
    private val config: ShadowsocksConfig = ShadowsocksConfig(
        methods = listOf(
            "chacha20-ietf-poly1305",
            "aes-256-gcm",
            "xchacha20-ietf-poly1305",
        ),
        passwords = emptyList(),
        ports = listOf(8388u, 8389u, 8390u),
    )

    fun enable() { enabled = true }
    fun disable() { enabled = false }

    private fun detectShadowsocks(data: ByteArray): Boolean {
        if (data.size < 32) return false
        val entropy = calculateEntropy(data.sliceArray(0 until 32))
        return entropy > 7.0f
    }

    private fun calculateEntropy(data: ByteArray): Float {
        val frequencies = IntArray(256)
        for (byte in data) {
            frequencies[byte.toInt() and 0xFF]++
        }
        val len = data.size.toFloat()
        var entropy = 0.0f
        for (count in frequencies) {
            if (count > 0) {
                val p = count / len
                entropy -= p * log2(p)
            }
        }
        return entropy
    }
}

suspend fun ShadowsocksGate.isOpen(): Boolean = enabled

suspend fun ShadowsocksGate.processData(data: ByteArray): Result<ByteArray> {
    if (!enabled) {
        return Result.failure(Exception("Shadowsocks gate is closed"))
    }
    if (!detectShadowsocks(data)) {
        return Result.failure(Exception("Not Shadowsocks protocol"))
    }
    println("Processing through Shadowsocks gate")
    return Result.success(data.copyOf())
}

fun ShadowsocksGate.gateName(): String = "shadowsocks"
fun ShadowsocksGate.gateChildren(): List<Gate> = emptyList()

private data class ShadowsocksConfig(
    val methods: List<String>,
    val passwords: List<String>,
    val ports: List<UShort>,
)
