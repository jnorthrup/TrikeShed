package cbadvanced

import dreamer.exchange.CoinbaseClient
import dreamer.exchange.CoinbaseCredentials
import dreamer.exchange.JvmHmacSigner
import dreamer.exchange.JvmHttpTransport
import dreamer.exchange.JwtSigner
import dreamer.exchange.RobinhoodBalance
import dreamer.exchange.RobinhoodHolding
import dreamer.exchange.loadCoinbaseApiConfig
import dreamer.exchange.loadCoinbaseCredentials
import java.nio.file.Path

data class CoinbaseAuthProof(
    val dotenvPath: Path,
    val keyName: String,
    val restUrl: String,
    val balance: RobinhoodBalance?,
    val holdings: List<RobinhoodHolding>? = null,
    val quoteProduct: String,
    val quote: Double?,
    val authSucceeded: Boolean,
    val message: String,
)

suspend fun runCoinbaseAuthProof(dotenvPath: Path, quoteProduct: String = "BTC"): CoinbaseAuthProof {
    val credentials = loadCredentials(dotenvPath)
    val apiConfig = loadCoinbaseApiConfig(dotenvPath.toString())
        ?: error("Coinbase API config missing in ${dotenvPath.toAbsolutePath().normalize()}")

    val client = CoinbaseClient(
        config = apiConfig,
        http = JvmHttpTransport(),
        signer = JvmHmacSigner(),
        jwtAuth = JwtSigner(),
    )
    client.initClock(System.currentTimeMillis(), 0L)

    val balance = client.getBalance()
    val quote = if (balance != null) client.getQuotes(listOf(quoteProduct))?.get(quoteProduct) else null

    return CoinbaseAuthProof(
        dotenvPath = dotenvPath.toAbsolutePath().normalize(),
        keyName = credentials.apiKeyId?.toString() ?: credentials.apiKey.toString(),
        restUrl = credentials.restUrl.toString(),
        balance = balance,
        quoteProduct = quoteProduct,
        quote = quote,
        authSucceeded = balance != null,
        message = if (balance != null) {
            "Coinbase Advanced Trade auth succeeded"
        } else {
            "Coinbase Advanced Trade auth returned 401 Unauthorized for the current .env credentials"
        },
    )
}

private fun loadCredentials(dotenvPath: Path): CoinbaseCredentials =
    loadCoinbaseCredentials(dotenvPath.toString())
        ?: error("Coinbase credentials missing in ${dotenvPath.toAbsolutePath().normalize()}")
