package borg.trikeshed.money.carlos

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * Minimal Kotlin port of the RobinhoodAPI wrapper used by flex.js
 * Uses OkHttp for HTTP and BouncyCastle for Ed25519 signing.
 */
class RobinhoodAPI(private val apiKey: String, base64Priv: String) {
    private val baseUrl = "https://trading.robinhood.com"
    private val retryDelay = 60_000L
    private val requestTimeoutMs = 20_000L
    private val client: OkHttpClient
    private val privateKeyParams: Ed25519PrivateKeyParameters

    init {
        val raw = Base64.getDecoder().decode(base64Priv)
        val seed = when (raw.size) {
            32 -> raw
            64 -> raw.copyOfRange(0, 32)
            else -> throw IllegalArgumentException("Private key must be 32 (seed) or 64 (secret key) bytes")
        }
        privateKeyParams = Ed25519PrivateKeyParameters(seed, 0)
        client = OkHttpClient.Builder()
            .connectTimeout(requestTimeoutMs, TimeUnit.MILLISECONDS)
            .callTimeout(requestTimeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(requestTimeoutMs, TimeUnit.MILLISECONDS)
            .build()
    }

    private fun timestampSeconds(): Long = System.currentTimeMillis() / 1000L

    private fun signMessage(msg: String): String {
        val signer = Ed25519Signer()
        signer.init(true, privateKeyParams)
        val bytes = msg.toByteArray(Charsets.UTF_8)
        signer.update(bytes, 0, bytes.size)
        val sig = signer.generateSignature()
        return Base64.getEncoder().encodeToString(sig)
    }

    @Throws(Exception::class)
    private fun request(method: String, path: String, bodyJson: String? = null): String? {
        val t = timestampSeconds()
        val bodyStr = bodyJson ?: ""
        val toSign = apiKey + t + path + method + bodyStr
        val signature = signMessage(toSign)

        val mediaType = "application/json".toMediaType()
        val url = baseUrl + path
        val builder = Request.Builder()
            .url(url)
            .addHeader("x-api-key", apiKey)
            .addHeader("x-signature", signature)
            .addHeader("x-timestamp", t.toString())
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "application/json")

        val req = when (method.uppercase()) {
            "GET" -> builder.get().build()
            "POST" -> builder.post(bodyStr.toRequestBody(mediaType)).build()
            "PUT" -> builder.put(bodyStr.toRequestBody(mediaType)).build()
            "DELETE" -> builder.delete(bodyStr.toRequestBody(mediaType)).build()
            else -> throw IllegalArgumentException("Unsupported HTTP method: $method")
        }

        client.newCall(req).execute().use { resp ->
            val code = resp.code
            val body = resp.body?.string()
            if (!resp.isSuccessful) {
                val errMsg = "HTTP $code -> ${body ?: "<no body>"}"
                throw RuntimeException(errMsg)
            }
            return body
        }
    }

    suspend fun requestWithRetry(method: String, path: String, bodyJson: String? = null): String? {
        while (true) {
            try {
                return request(method, path, bodyJson)
            } catch (err: Exception) {
                val msg = err.message ?: "unknown"
                val isRetryable =
                    msg.contains("timeout", ignoreCase = true) ||
                        msg.contains("ECONNRESET") ||
                        msg.contains("ENOTFOUND") ||
                        msg.contains("ETIMEDOUT") ||
                        msg.contains("EAI_AGAIN") ||
                        msg.contains("ECONNREFUSED") ||
                        msg.contains("429") ||
                        (msg.contains("5") && msg.contains("HTTP")) ||
                        msg.contains("403")
                if (isRetryable) {
                    println("⏳ API Error ($path): $msg. Retrying in ${retryDelay / 1000}s...")
                    try { Thread.sleep(retryDelay) } catch (_: InterruptedException) { }
                } else {
                    println("❌ API Error ($path): Non-retryable: $msg")
                    throw err
                }
            }
        }
    }

    suspend fun getBalance(): Double {
        val data = requestWithRetry("GET", "/api/v1/crypto/trading/accounts/")
        if (data == null) return 0.0
        return try {
            val mapper = com.fasterxml.jackson.module.kotlin.jacksonObjectMapper()
            val node = mapper.readTree(data)
            val account = if (node.has("results") && node.get("results").isArray) node.get("results").get(0) else node
            val fields = listOf("buying_power", "cash_balance", "crypto_buying_power")
            for (field in fields) {
                if (account.has(field) && !account.get(field).isNull) {
                    return account.get(field).asDouble(0.0)
                }
            }
            println("[getBalance] Could not find a recognizable balance field in response: $data")
            0.0
        } catch (e: Exception) {
            println("[getBalance] parse error: ${e.message}")
            0.0
        }
    }

    suspend fun getHoldings(): List<Map<String, Any>> {
        val data = requestWithRetry("GET", "/api/v1/crypto/trading/holdings/")
        if (data == null) return emptyList()
        val mapper = com.fasterxml.jackson.module.kotlin.jacksonObjectMapper()
        return try {
            val root = mapper.readTree(data)
            val arr = if (root.has("results") && root.get("results").isArray) root.get("results") else null
            if (arr == null) return emptyList()
            val list = mutableListOf<Map<String, Any>>()
            arr.forEach { node -> list.add(mapper.convertValue(node, Map::class.java) as Map<String, Any>) }
            list
        } catch (e: Exception) {
            println("[getHoldings] parse error: ${e.message}")
            emptyList()
        }
    }

    suspend fun getQuotes(assetCodes: List<String>): Map<String, Double> {
        if (assetCodes.isEmpty()) return emptyMap()
        val data = requestWithRetry("GET", "/api/v1/crypto/marketdata/best_bid_ask/")
        if (data == null) return emptyMap()
        val mapper = com.fasterxml.jackson.module.kotlin.jacksonObjectMapper()
        return try {
            val root = mapper.readTree(data)
            val quotes = root.get("results") ?: return emptyMap()
            val result = mutableMapOf<String, Double>()
            val codesSet = assetCodes.toSet()
            quotes.forEach { q ->
                val symRaw = q.get("symbol")?.asText()
                val sym = symRaw?.replace("-USD", "")
                if (sym != null && codesSet.contains(sym)) {
                    val rawPrice = q.get("price")?.asDouble()
                    if (rawPrice != null) {
                        result[sym] = String.format("%.10f", rawPrice).toDouble()
                    } else {
                        println("[getQuotes] Invalid price for $sym: ${q.get("price")}")
                        result[sym] = 0.0
                    }
                }
            }
            assetCodes.forEach { if (!result.containsKey(it)) println("[getQuotes] No quote data for $it") }
            result
        } catch (e: Exception) {
            println("[getQuotes] parse error: ${e.message}")
            emptyMap()
        }
    }

    suspend fun placeSell(symbol: String, quantityStr: String): Map<String, Any?> {
        validateOrderParams(symbol, quantityStr, "sell")
        val path = "/api/v1/crypto/trading/orders/"
        val body = mapOf(
            "client_order_id" to java.util.UUID.randomUUID().toString(),
            "side" to "sell",
            "type" to "market",
            "symbol" to symbol,
            "market_order_config" to mapOf("asset_quantity" to quantityStr)
        )
        val mapper = com.fasterxml.jackson.module.kotlin.jacksonObjectMapper()
        val resp = requestWithRetry("POST", path, mapper.writeValueAsString(body))
        return if (resp == null) emptyMap() else mapper.readValue(resp, Map::class.java) as Map<String, Any?>
    }

    suspend fun placeBuy(symbol: String, quantityStr: String): Map<String, Any?> {
        validateOrderParams(symbol, quantityStr, "buy")
        val path = "/api/v1/crypto/trading/orders/"
        val body = mapOf(
            "client_order_id" to java.util.UUID.randomUUID().toString(),
            "side" to "buy",
            "type" to "market",
            "symbol" to symbol,
            "market_order_config" to mapOf("asset_quantity" to quantityStr)
        )
        val mapper = com.fasterxml.jackson.module.kotlin.jacksonObjectMapper()
        val resp = requestWithRetry("POST", path, mapper.writeValueAsString(body))
        return if (resp == null) emptyMap() else mapper.readValue(resp, Map::class.java) as Map<String, Any?>
    }

    private fun validateOrderParams(symbol: String, quantityStr: String, side: String) {
        if (!symbol.contains("-USD")) throw IllegalArgumentException("Invalid symbol format: '$symbol'. Must be like 'BTC-USD'.")
        val qtyNum = quantityStr.toDoubleOrNull() ?: throw IllegalArgumentException("Invalid quantity for $side order: '$quantityStr'. Must be a positive number.")
        if (qtyNum <= 0.0) throw IllegalArgumentException("Invalid quantity for $side order: '$quantityStr'. Must be a positive number.")
    }
}
