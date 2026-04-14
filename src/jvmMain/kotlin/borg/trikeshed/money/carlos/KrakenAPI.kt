package borg.trikeshed.money.carlos

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Enhanced Kraken API helper for the Kotlin port.
 * Implements public requests (Assets/AssetPairs), a lightweight public
 * WebSocket client to mimic the JS port's startup behavior, and a
 * minimal private request signer used by Balance (getHoldings).
 */
class KrakenAPI(private val apiKey: String, private val apiSecretBase64: String) {
    private val client = OkHttpClient()
    private val mapper = jacksonObjectMapper().registerKotlinModule()
    private val lastNonce = AtomicLong(System.currentTimeMillis() * 1000)
    private val krakenAssetMap = mapOf(
        "XXBT" to "BTC", "XBT" to "BTC",
        "ETH" to "ETH", "XETH" to "ETH",
        "SOL" to "SOL",
        "ADA" to "ADA", "XLM" to "XLM", "AVAX" to "AVAX", "XRP" to "XRP", "XXRP" to "XRP",
        "LINK" to "LINK", "UNI" to "UNI",
        "XXDG" to "DOGE",
        "PEPE" to "PEPE", "BONK" to "BONK", "SHIB" to "SHIB", "WIF" to "WIF",
        "POPCAT" to "POPCAT",
        "PENGU" to "PENGU", "PNUT" to "PNUT",
        "ALGO" to "ALGO", "BCH" to "BCH", "XTZ" to "XTZ", "ETC" to "ETC",
        "ICP" to "ICP",
        "COMP" to "COMP",
        "TRUMP" to "TRUMP",
        "LTC" to "LTC", "XLTC" to "LTC",
        "TRX" to "TRX",
        "SUI" to "SUI",
        "ZUSD" to "USD",
        "USD" to "USD",
        "FET" to "FET"
    )
    private val krakenAssetPreference = listOf("XXBT", "XETH", "XXRP", "XLTC", "XXDG", "ZUSD", "USDC")
    private val symbolToKrakenAsset = buildSymbolToKrakenAsset()
    private val krakenQuoteAsset = symbolToKrakenAsset["USD"] ?: "ZUSD"

    val assetInfo: MutableMap<String, Map<String, Any?>> = mutableMapOf()
    val pairInfo: MutableMap<String, Map<String, Any?>> = mutableMapOf()
    val pairNameToWsName: MutableMap<String, String> = mutableMapOf()

    private val wsPublicUrl = "wss://ws.kraken.com"
    @Volatile private var wsPublic: WebSocket? = null
    @Volatile private var wsPublicConnected = false
    private val latestPrices: ConcurrentHashMap<String, Double> = ConcurrentHashMap()
    private val activeSubscriptions = ConcurrentHashMap.newKeySet<String>()
    private val krakenAssetToCommonSymbol: MutableMap<String, String> = mutableMapOf()

    fun initialize() {
        println("[API] Initializing Kraken API client (Enhanced Version)...")
        val assets = requestPublic("Assets") as? Map<String, Any?>
        val pairs = requestPublic("AssetPairs") as? Map<String, Any?>
        assets?.forEach { (k, v) -> (v as? Map<String, Any?>)?.let { assetInfo[k] = it } }
        pairs?.forEach { (k, v) -> (v as? Map<String, Any?>)?.let { pairInfo[k] = it } }
        pairInfo.forEach { (pairName, data) ->
            data["wsname"]?.toString()?.let { pairNameToWsName[pairName] = it }
        }
        rebuildKrakenAssetMap()
        println("[API] Fetched info for ${assetInfo.size} assets and ${pairInfo.size} pairs.")

        connectPublicWebSocket()
        println("[API] WebSocket connections initiated...")
        try { Thread.sleep(2500) } catch (_: InterruptedException) { }
    }

    private fun buildSymbolToKrakenAsset(): Map<String, String> {
        val mapping = linkedMapOf<String, String>()
        val assigned = mutableSetOf<String>()
        for (preferred in krakenAssetPreference) {
            val common = krakenAssetMap[preferred] ?: continue
            if (assigned.add(common)) {
                mapping[common] = preferred
            }
        }
        for ((krakenAsset, common) in krakenAssetMap) {
            if (assigned.add(common)) {
                mapping[common] = krakenAsset
            }
        }
        return mapping
    }

    private fun rebuildKrakenAssetMap() {
        krakenAssetToCommonSymbol.clear()
        val mappedCommonSymbols = mutableSetOf<String>()
        for ((krakenAsset, common) in krakenAssetMap) {
            if (assetInfo.containsKey(krakenAsset)) {
                val preferred = symbolToKrakenAsset[common]
                if (preferred == krakenAsset || mappedCommonSymbols.add(common)) {
                    krakenAssetToCommonSymbol[krakenAsset] = common
                }
            }
        }
        for ((krakenAsset, data) in assetInfo) {
            if (krakenAssetToCommonSymbol.containsKey(krakenAsset)) continue
            val altname = data["altname"]?.toString() ?: continue
            val preferred = krakenAssetMap.entries.firstOrNull { it.value == altname }?.key
            if (preferred == null || preferred == krakenAsset) {
                krakenAssetToCommonSymbol[krakenAsset] = altname
            }
        }
        if (!krakenAssetToCommonSymbol.containsKey(krakenQuoteAsset)) {
            krakenAssetToCommonSymbol[krakenQuoteAsset] = "USD"
        }
    }

    private fun requestPublic(endpoint: String): Any? {
        val url = "https://api.kraken.com/0/public/$endpoint"
        val req = Request.Builder().url(url).get().build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw Exception("HTTP ${resp.code}")
            val body = resp.body?.string() ?: throw Exception("Empty response from Kraken public API")
            val map: Map<String, Any?> = mapper.readValue(body)
            return map["result"]
        }
    }

    private fun signRequest(path: String, nonce: String, body: String): String {
        val sha256 = MessageDigest.getInstance("SHA-256").digest((nonce + body).toByteArray(Charsets.UTF_8))
        val pathBytes = path.toByteArray(Charsets.UTF_8)
        val combined = ByteArray(pathBytes.size + sha256.size)
        System.arraycopy(pathBytes, 0, combined, 0, pathBytes.size)
        System.arraycopy(sha256, 0, combined, pathBytes.size, sha256.size)
        val secret = Base64.getDecoder().decode(apiSecretBase64)
        val mac = Mac.getInstance("HmacSHA512")
        mac.init(SecretKeySpec(secret, "HmacSHA512"))
        val sig = mac.doFinal(combined)
        return Base64.getEncoder().encodeToString(sig)
    }

    private fun requestPrivate(endpoint: String, params: Map<String, String> = emptyMap()): Any? {
        val nonce = nextNonce().toString()
        val encodedParams = params.map { (k, v) -> "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}" }
        val bodyString = (listOf("nonce=$nonce") + encodedParams).joinToString("&")
        val path = "/0/private/$endpoint"
        val apiSign = try { signRequest(path, nonce, bodyString) } catch (e: Exception) { throw Exception("Failed to sign request: ${e.message}") }
        val mediaType = "application/x-www-form-urlencoded; charset=utf-8".toMediaType()
        val body = RequestBody.create(mediaType, bodyString)
        val req = Request.Builder()
            .url("https://api.kraken.com$path")
            .post(body)
            .addHeader("API-Key", apiKey)
            .addHeader("API-Sign", apiSign)
            .build()
        client.newCall(req).execute().use { resp ->
            val responseBody = resp.body?.string() ?: throw Exception("Empty response from Kraken private API")
            val map: Map<String, Any?> = mapper.readValue(responseBody)
            val errors = map["error"] as? List<*>
            if (errors != null && errors.isNotEmpty()) {
                throw Exception(errors.joinToString(", ") { it.toString() })
            }
            return map["result"]
        }
    }

    private fun nextNonce(): Long {
        while (true) {
            val current = System.currentTimeMillis() * 1000
            val previous = lastNonce.get()
            val next = maxOf(previous + 1, current)
            if (lastNonce.compareAndSet(previous, next)) {
                return next
            }
        }
    }

    fun getHoldings(): Pair<Double, List<Map<String, Any?>>> {
        val result = requestPrivate("Balance") as? Map<String, Any?> ?: throw Exception("Invalid Balance response")
        var quoteBalance = 0.0
        val holdingsList = mutableListOf<Map<String, Any?>>()
        for ((k, v) in result) {
            val qty = v?.toString()?.toDoubleOrNull() ?: continue
            val commonSymbol = krakenAssetToCommonSymbol[k]
            if (k == krakenQuoteAsset || commonSymbol == "USD") {
                quoteBalance += qty
            } else if (commonSymbol != null && qty > 1e-12) {
                val existing = holdingsList.firstOrNull { it["asset_code"] == commonSymbol }
                if (existing != null) {
                    val mergedAssets = ((existing["kraken_assets"] as? List<*>) ?: emptyList<Any?>()) + k
                    holdingsList[holdingsList.indexOf(existing)] = mapOf(
                        "asset_code" to commonSymbol,
                        "total_quantity" to (((existing["total_quantity"] as? Double) ?: 0.0) + qty),
                        "kraken_assets" to mergedAssets
                    )
                } else {
                    holdingsList.add(mapOf("asset_code" to commonSymbol, "total_quantity" to qty, "kraken_assets" to listOf(k)))
                }
            }
        }
        return Pair(quoteBalance, holdingsList)
    }

    fun getKrakenPairName(commonSymbol: String): String? {
        val krakenBaseAsset = symbolToKrakenAsset[commonSymbol]
        val baseAssetsToTry = if (commonSymbol == "BTC") {
            listOfNotNull(krakenBaseAsset, "XBT", "XXBT").distinct()
        } else {
            listOf(krakenBaseAsset ?: commonSymbol)
        }
        for (base in baseAssetsToTry) {
            for ((pairKey, data) in pairInfo) {
                val pairBase = data["base"]?.toString()
                val pairQuote = data["quote"]?.toString()
                if (pairBase == base && pairQuote == krakenQuoteAsset) {
                    return pairKey
                }
                if (pairKey == "$base$krakenQuoteAsset") {
                    return pairKey
                }
            }
        }
        for (base in baseAssetsToTry) {
            val guess = "${base}USD"
            if (pairInfo.containsKey(guess)) {
                return guess
            }
        }
        return null
    }

    fun getPairData(commonSymbol: String): Map<String, Any?>? {
        val pairName = getKrakenPairName(commonSymbol) ?: return null
        return pairInfo[pairName]
    }

    private fun getKrakenPairWsName(commonSymbol: String): String? {
        val pairName = getKrakenPairName(commonSymbol) ?: return null
        return pairNameToWsName[pairName]
    }

    fun getLatestPrice(commonSymbol: String): Double? {
        val wsname = getKrakenPairWsName(commonSymbol) ?: return null
        return if (wsPublicConnected) latestPrices[wsname] else null
    }

    fun subscribeToTickers(commonSymbols: List<String>) {
        val ws = wsPublic ?: run {
            println("[API] WebSocket not connected, subscription deferred.")
            return
        }
        val wsNames = commonSymbols.mapNotNull(::getKrakenPairWsName).distinct()
        if (wsNames.isEmpty()) return
        for (name in wsNames) {
            activeSubscriptions.add(name)
        }
        val msg = mapOf("event" to "subscribe", "pair" to wsNames, "subscription" to mapOf("name" to "ticker"))
        val json = mapper.writeValueAsString(msg)
        ws.send(json)
    }

    fun isWsConnected(): Boolean = wsPublicConnected

    private fun connectPublicWebSocket() {
        try {
            println("[API] 🔌 Connecting to Public WebSocket: $wsPublicUrl")
            val req = Request.Builder().url(wsPublicUrl).build()
            wsPublic = client.newWebSocket(req, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    wsPublicConnected = true
                    println("[API] ✅ Public WebSocket Connected.")
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    try {
                        if (text.trimStart().startsWith("{")) {
                            val message: Map<String, Any?> = mapper.readValue(text)
                            if ((message["event"] as? String) == "systemStatus") {
                                val status = message["status"]?.toString()?.uppercase() ?: "UNKNOWN"
                                println("[API] Public WS System Status: $status")
                            }
                        } else {
                            val message: List<Any?> = mapper.readValue(text)
                            if (message.size >= 4 && message[2] == "ticker" && message[3] is String) {
                                val payload = message[1] as? Map<*, *>
                                val close = (payload?.get("c") as? List<*>)?.firstOrNull()?.toString()?.toDoubleOrNull()
                                if (close != null && close > 0) {
                                    latestPrices[message[3].toString()] = close
                                }
                            }
                        }
                    } catch (e: Exception) {
                        System.err.println("[API] Error processing Public WS message: ${e.message}")
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    System.err.println("[API] Public WebSocket Error: ${t.message}")
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    wsPublicConnected = false
                    println("[API] Public WebSocket Closed: $code - $reason")
                }
            })
        } catch (e: Exception) {
            System.err.println("[API] Failed to connect public WS: ${e.message}")
        }
    }

    fun close() {
        try {
            println("[API] Closing API connections...")
            if (wsPublic != null) {
                println("[API] Closing Public WS...")
                wsPublic?.close(1000, "Closing")
            }
            wsPublicConnected = false
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
            println("[API] API connections closed.")
        } catch (_: Exception) {
        }
    }
}
