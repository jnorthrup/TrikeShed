package borg.trikeshed.money.carlos

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.math.max
import kotlin.system.exitProcess

private const val QUOTE_CURRENCY = "USD"
private const val REFRESH_INTERVAL_MS = 8000L
private const val STATE_FILE_NAME = "krakenBotState.json"

private data class SkimmerState(
    val baselines: MutableMap<String, Double> = mutableMapOf(),
    val trailingState: MutableMap<String, Any?> = mutableMapOf(),
    val lastActionTimestamps: MutableMap<String, Long> = mutableMapOf(),
)

object KrakenSkimmer {
    private val mapper = jacksonObjectMapper().registerKotlinModule()

    private val stateFilePath: Path by lazy {
        val cwd = Path.of(System.getProperty("user.dir"))
        val local = cwd.resolve(STATE_FILE_NAME)
        val donor = cwd.resolve("money/src/main/java/carlos/$STATE_FILE_NAME")
        when {
            Files.exists(local) -> local
            Files.exists(donor) -> donor
            else -> local
        }
    }

    private val tokenBaselines = mutableMapOf<String, Double>()
    private val trailingState = mutableMapOf<String, Any?>()
    private val lastActionTimestamps = mutableMapOf<String, Long>()

    @Volatile
    private var initialized = false

    @Volatile
    private var shuttingDown = false

    private fun loadState() {
        try {
            if (Files.exists(stateFilePath)) {
                val loaded: Map<String, Any?> = mapper.readValue(stateFilePath.toFile())
                tokenBaselines.clear()
                trailingState.clear()
                lastActionTimestamps.clear()
                @Suppress("UNCHECKED_CAST")
                tokenBaselines.putAll((loaded["baselines"] as? Map<String, Number>).orEmpty().mapValues { it.value.toDouble() })
                @Suppress("UNCHECKED_CAST")
                trailingState.putAll((loaded["trailingState"] as? Map<String, Any?>).orEmpty())
                @Suppress("UNCHECKED_CAST")
                lastActionTimestamps.putAll((loaded["lastActionTimestamps"] as? Map<String, Number>).orEmpty().mapValues { it.value.toLong() })
                println("✅ Loaded state (Baselines, TrailingState, LastActionTimestamps) from $stateFilePath.")
            } else {
                println("ℹ️ State file $stateFilePath not found, starting fresh.")
            }
        } catch (e: Exception) {
            System.err.println("❌ Error loading state from $stateFilePath: ${e.message}")
        }
    }

    private fun saveState() {
        try {
            val tempFile = stateFilePath.resolveSibling("${stateFilePath.fileName}.tmp")
            val state = SkimmerState(
                baselines = tokenBaselines.toMutableMap(),
                trailingState = trailingState.toMutableMap(),
                lastActionTimestamps = lastActionTimestamps.toMutableMap(),
            )
            mapper.writerWithDefaultPrettyPrinter().writeValue(tempFile.toFile(), state)
            Files.move(tempFile, stateFilePath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: Exception) {
            System.err.println("🚨 CRITICAL ERROR: Failed to save state: ${e.message}")
        }
    }

    private fun installShutdownHook(api: KrakenAPI) {
        Runtime.getRuntime().addShutdownHook(Thread {
            if (shuttingDown) return@Thread
            shuttingDown = true
            println()
            println("🚦 Received SIGTERM. Shutting down...")
            println("💾 Saving final state...")
            saveState()
            println("🔌 Closing API connections...")
            api.close()
            println("✅ Shutdown complete.")
        })
    }

    @JvmStatic
    fun main(args: Array<String>) {
        println("🏁 Starting Kraken Cryptobot Script (v2.4.1-API-Merged-Fix2)...")
        loadState()

        val apiKey = System.getenv("KRAKEN_API_KEY")
        val apiSecret = System.getenv("KRAKEN_PRIVATE_KEY")
        if (apiKey.isNullOrBlank() || apiSecret.isNullOrBlank()) {
            System.err.println("❌ FATAL: Missing Kraken keys.")
            exitProcess(1)
        }

        println("🚀 Initializing Kraken CryptoBot (v2.4.1-API-Merged-Fix2)...")
        val api = try {
            KrakenAPI(apiKey, apiSecret)
        } catch (e: Exception) {
            System.err.println("❌ FATAL: Failed to construct KrakenAPI: ${e.message}")
            exitProcess(1)
        }

        try {
            api.initialize()
            println("🔑 Kraken API Initialized (Enhanced Version).")
        } catch (e: Exception) {
            System.err.println("❌ FATAL: Kraken init error: ${e.message}")
            exitProcess(1)
        }

        installShutdownHook(api)

        try {
            while (true) {
                runCycle(api)
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (e: Exception) {
            System.err.println("💥 FATAL ERROR in main execution scope: ${e.message}")
            println("💾 Attempting to save state on error...")
            saveState()
            api.close()
            exitProcess(1)
        }
    }

    private fun runCycle(api: KrakenAPI) {
        val startTime = System.currentTimeMillis()
        println()
        println("----- Cycle Start: ${Instant.now().truncatedTo(ChronoUnit.MILLIS)} -----")

        val (cashBalance, holdings) = try {
            api.getHoldings()
        } catch (e: Exception) {
            System.err.println("❌ ERROR: Balance/Holdings fetch failed: ${e.message}")
            Thread.sleep(REFRESH_INTERVAL_MS)
            return
        }

        val codes = holdings.mapNotNull { it["asset_code"]?.toString() }.sorted()
        println("💰 Available Balance: $${"%.2f".format(cashBalance)} $QUOTE_CURRENCY")
        if (codes.isNotEmpty()) {
            println("📊 Holdings: ${codes.joinToString(", ")}")
        } else {
            println("ℹ️ No Holdings Found.")
        }

        if (!initialized && holdings.isEmpty()) {
            println("✅ No holdings, baseline init complete.")
            initialized = true
        }

        val totalHoldingsValue = 0.0
        val totalPortfolioValue = totalHoldingsValue + cashBalance
        println("--- Financial Overview ---")
        println("Total Holdings Value:   $${"%.2f".format(totalHoldingsValue)}")
        println("Cash Balance:           $${"%.2f".format(cashBalance)} $QUOTE_CURRENCY")
        println("Total Portfolio Value:  $${"%.2f".format(totalPortfolioValue)}")
        println("Deviation (Managed):    \u001B[32m+$0.00 (0.00%)\u001B[0m (0 Assets)")
        println("--------------------------")
        println()

        if (holdings.isEmpty()) {
            println("🧘 No holdings, skipping trading actions.")
        }

        val elapsed = System.currentTimeMillis() - startTime
        val delay = max(0L, REFRESH_INTERVAL_MS - elapsed)
        println("----- Cycle End: Took ${elapsed}ms. Waiting ${delay}ms... -----")
        Thread.sleep(delay)
    }
}
