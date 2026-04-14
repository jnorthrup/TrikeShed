package borg.trikeshed.money.carlos

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant

/**
 * Kotlin equivalent of tradeHistory.js
 */
data class TradeRecord(
    val asset: String? = null,
    val side: String? = null,
    val orderType: String? = null,
    val quantity: String? = null,
    val effectivePrice: String? = null,
    val totalValue: String? = null,
    val clientOrderId: String? = null,
    val extra: Map<String, Any>? = null,
    val timestamp: String? = null
)

object TradeHistory {
    private val mapper: ObjectMapper = jacksonObjectMapper().registerKotlinModule()
    private val tradeHistoryFile: Path = Path.of(System.getProperty("user.dir")).resolve("trade_history.log")

    fun appendTradeHistory(record: TradeRecord) {
        val withTimestamp = if (record.timestamp == null) {
            record.copy(timestamp = Instant.now().toString())
        } else {
            record
        }
        val json = mapper.writeValueAsString(withTimestamp)
        try {
            Files.createDirectories(tradeHistoryFile.parent ?: Path.of("."))
            Files.writeString(tradeHistoryFile, json + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND)
        } catch (e: Exception) {
            System.err.println("Error appending trade history: ${e.message}")
            e.printStackTrace()
        }
    }
}

fun appendTradeHistory(record: TradeRecord) {
    TradeHistory.appendTradeHistory(record)
}
