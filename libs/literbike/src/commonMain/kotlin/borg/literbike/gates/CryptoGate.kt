package borg.literbike.gates

import kotlin.math.log2

/**
 * Crypto Gate for LITEBIKE
 * Gates all cryptographic operations
 * Ported from literbike/src/gates/crypto_gate.rs
 */
class CryptoGate {
    private var enabled: Boolean = false
    private val allowedMethods: MutableMap<String, Boolean> = mutableMapOf(
        "aes-128-gcm" to false,
        "aes-256-gcm" to false,
        "chacha20-poly1305" to false,
        "xchacha20-poly1305" to false,
        "aes-128-cfb" to false,
        "aes-256-cfb" to false,
        "aes-128-ctr" to false,
        "aes-256-ctr" to false,
        "blake3-chacha20-poly1305" to false,
        "aes-256-gcm-siv" to false,
    )

    fun enableMethod(method: String) {
        allowedMethods[method]?.let {
            allowedMethods[method] = true
            println("Crypto gate: Enabled $method")
        }
    }

    fun disableMethod(method: String) {
        allowedMethods[method]?.let {
            allowedMethods[method] = false
            println("Crypto gate: Disabled $method")
        }
    }

    fun enableAll() {
        enabled = true
        allowedMethods.keys.forEach { method ->
            allowedMethods[method] = true
            println("Crypto gate: Enabled $method")
        }
    }

    fun disableAll() {
        enabled = false
        allowedMethods.keys.forEach { method ->
            allowedMethods[method] = false
        }
        println("Crypto gate: All methods disabled")
    }

    fun isMethodAllowed(method: String): Boolean = allowedMethods[method] ?: false
}

/** Async extension to make CryptoGate implement Gate interface */
suspend fun CryptoGate.isOpen(): Boolean = enabled

suspend fun CryptoGate.processData(data: ByteArray): Result<ByteArray> {
    if (!enabled) {
        return Result.failure(Exception("Crypto gate is closed"))
    }
    val entropy = calculateEntropy(data)
    if (entropy < 6.0f) {
        return Result.failure(Exception("Data doesn't appear to be encrypted"))
    }
    println("Processing through crypto gate (entropy: %.2f)".format(entropy))
    return Result.success(data.copyOf())
}

fun CryptoGate.gateName(): String = "crypto"
fun CryptoGate.gateChildren(): List<Gate> = emptyList()

private fun calculateEntropy(data: ByteArray): Float {
    if (data.isEmpty()) return 0.0f

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
