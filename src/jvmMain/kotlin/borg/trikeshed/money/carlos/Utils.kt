package borg.trikeshed.money.carlos

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import java.io.File
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

private val mapper: ObjectMapper = jacksonObjectMapper().registerKotlinModule()

val minIncrementMap: Map<String, Double> = mapOf(
    "AAVE" to 0.00001, "COMP" to 0.00001, "XLM" to 0.01, "AVAX" to 0.0001, "ADA" to 0.01,
    "XRP" to 0.001, "LINK" to 0.0001, "UNI" to 0.0001, "SOL" to 0.00001, "DOGE" to 0.01,
    "SHIB" to 1.0, "BTC" to 0.00000001, "ETH" to 0.000001, "PEPE" to 1.0, "BONK" to 1.0,
    "USDC" to 0.000001, "POPCAT" to 0.01, "WIF" to 1.0, "PENGU" to 1.00, "PNUT" to 0.01,
    "BCH" to 0.00001, "XTZ" to 0.001, "ETC" to 0.000001, "ARB" to 0.010, "LTC" to 0.0001
)

const val STATE_FILE_PATH = "botState.json"
const val PRECISION_THRESHOLD = 1e-9

fun roundQty(sym: String, qty: Double?): String {
    val step = minIncrementMap[sym] ?: if (sym == "LTC") 0.0001 else 0.00000001
    if (qty == null || qty.isNaN() || qty < (step / 10)) return "0.0"
    val rounded = floor(qty / step) * step
    val decimalPlaces = step.toString().substringAfter('.', "").length
    var str = "%.${min(18, max(8, decimalPlaces))}f".format(rounded)
    str = str.replace(Regex("(\\.\\d*?[1-9])0+$"), "$1")
    str = str.replace(Regex("\\.0+$"), "")
    return if (str.toDouble() < (step / 10)) "0.0" else str
}

fun logTrade(asset: String, side: String, quantity: String, price: String, clientOrderId: String, note: String = "") {
    try {
        val totalValue = try { (quantity.toDouble() * price.toDouble()).toString() } catch (_: Exception) { "0" }
        appendTradeHistory(
            TradeRecord(
                asset = asset,
                side = side.uppercase(),
                orderType = "market",
                quantity = quantity,
                effectivePrice = price,
                totalValue = totalValue,
                clientOrderId = clientOrderId,
                extra = mapOf("note" to note)
            )
        )
    } catch (e: Exception) {
        System.err.println("Error logging trade: ${e.message}")
    }
}

fun getEffectivePriceFromResp(resp: Map<String, Any?>?, fallbackPrice: Double?): Double? {
    if (resp == null) return fallbackPrice
    val priceVal: Any? = when {
        resp["average_price"] != null -> resp["average_price"]
        resp["executions"] is List<*> -> (resp["executions"] as List<*>).firstOrNull()?.let { (it as? Map<*, *>)?.get("effective_price") }
        resp["price"] != null -> resp["price"]
        else -> fallbackPrice?.toString()
    }
    if (priceVal == null) return null
    return try { priceVal.toString().toDouble().takeIf { !it.isNaN() && it > 0 } } catch (_: Exception) { null }
}

fun loadState(): Triple<Map<String, Double>, Map<String, Any>, Map<String, Long>> {
    try {
        val f = File(STATE_FILE_PATH)
        if (!f.exists()) {
            println("ℹ️ $STATE_FILE_PATH not found, starting with fresh state.")
            return Triple(emptyMap(), emptyMap(), emptyMap())
        }
        val map: Map<String, Any> = mapper.readValue(f)
        val baselines = (map["baselines"] as? Map<String, Double>) ?: emptyMap()
        val trailing = (map["trailingState"] as? Map<String, Any>) ?: emptyMap()
        val last = (map["lastActionTimestamps"] as? Map<String, Long>) ?: emptyMap()
        return Triple(baselines, trailing, last)
    } catch (e: Exception) {
        System.err.println("Error loading state: ${e.message}")
        return Triple(emptyMap(), emptyMap(), emptyMap())
    }
}

fun saveState(baselines: Map<String, Double>, trailing: Map<String, Any>, last: Map<String, Long>) {
    try {
        val stateToSave = mapOf("baselines" to baselines, "trailingState" to trailing, "lastActionTimestamps" to last)
        val tmp = File("$STATE_FILE_PATH.tmp")
        mapper.writeValue(tmp, stateToSave)
        tmp.renameTo(File(STATE_FILE_PATH))
    } catch (e: Exception) {
        System.err.println("Failed to save state: ${e.message}")
    }
}
