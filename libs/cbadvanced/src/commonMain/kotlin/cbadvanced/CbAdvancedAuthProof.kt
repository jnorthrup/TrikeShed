package cbadvanced

import borg.trikeshed.userspace.nio.file.Path
import dreamer.exchange.CoinbaseClient
import dreamer.exchange.CoinbaseCredentials
import dreamer.exchange.*
import dreamer.exchange.loadCoinbaseApiConfig
import dreamer.exchange.loadCoinbaseCredentials
import kotlin.time.Clock

data class CoinbaseAuthProof(
    val dotenvPath: Path,
    val keyName: CharSequence,
    val restUrl: CharSequence,
    val balance: RobinhoodBalance?,
    val quoteProduct: CharSequence,
    val quote: Double?,
    val authSucceeded: Boolean,
    val message: CharSequence,
)

suspend fun runCoinbaseAuthProof(dotenvPath: Path, quoteProduct: CharSequence = "BTC"): CoinbaseAuthProof {
    val credentials = loadCredentials(dotenvPath)
    val apiConfig = loadCoinbaseApiConfig(dotenvPath.toString())
        ?: error("Coinbase API config missing in $dotenvPath")

    val client = CoinbaseClient(
        config = apiConfig,
        http =  HttpTransport(),
        signer = HmacSigner(),
        jwtAuth = Signer(),
    )
    client.initClock(Clock.System.now().toEpochMilliseconds(), 0L)

    val balance = client.getBalance()
    val quote = if (balance != null) client.getQuotes(listOf(quoteProduct))?.get(quoteProduct) else null

    return CoinbaseAuthProof(
        dotenvPath = dotenvPath,
        keyName = credentials.apiKeyId ?: credentials.apiKey,
        restUrl = credentials.restUrl,
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
        ?: error("Coinbase credentials missing in $dotenvPath")
