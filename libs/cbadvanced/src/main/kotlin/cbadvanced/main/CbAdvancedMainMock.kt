package cbadvanced.main

import cbadvanced.*
import dreamer.exchange.RobinhoodHolding
import kotlinx.coroutines.runBlocking
import java.nio.file.Path

/**
 * Test version of CbAdvancedMain that uses mock client instead of real API calls.
 * This demonstrates the client logic works correctly without requiring valid API credentials.
 */
suspend fun main(args: Array<String>) {
    val requestedProduct = args.firstOrNull()?.ifBlank { null }?.substringBefore("-USD") ?: "BTC"

    println("=".repeat(60))
    println("Coinbase Advanced Trade Client - MOCK MODE")
    println("=".repeat(60))
    println()
    println("Using mock client to verify code logic without real API credentials")
    println()

    val mockClient = MockCoinbaseClient()
    val proof = runCoinbaseAuthProofMock(mockClient, requestedProduct)

    println(proof.message)
    println()
    println("─".repeat(60))
    println("Mock Results:")
    println("─".repeat(60))
    println("mode: mock (no real API calls)")
    println("authSucceeded: ${proof.authSucceeded}")
    println("cashBalance: $${proof.balance?.cashBalance ?: "N/A"}")
    println("buyingPower: $${proof.balance?.buyingPower ?: "N/A"}")
    println("quote $requestedProduct-USD: $${proof.quote ?: "N/A"}")
    println()

    if (proof.balance != null) {
        println("Holdings:")
        proof.holdings?.forEach { holding ->
            println("  ${holding.assetCode}: ${holding.quantity} @ $${holding.costBasis}")
        }
        println()
    }

    println("─".repeat(60))
    println("Next Steps:")
    println("─".repeat(60))
    println("1. Get valid API credentials from https://cloud.coinbase.com/keys")
    println("2. Update .env file with new credentials")
    println("3. Run: ./gradlew :libs:cbadvanced:authProof")
    println()
    println("See COINBASE_AUTH_ISSUE.md for full troubleshooting guide")
    println("=".repeat(60))
}

suspend fun runCoinbaseAuthProofMock(client: MockCoinbaseClient, quoteProduct: String = "BTC"): CoinbaseAuthProof {
    val balance = client.getBalance()
    val quote = if (balance != null) client.getQuotes(listOf(quoteProduct))?.get(quoteProduct) else null
    val holdings = if (balance != null) client.getHoldings() else null

    return CoinbaseAuthProof(
        dotenvPath = Path.of(System.getProperty("user.dir")).resolve(".env"),
        keyName = "mock-test-key",
        restUrl = "https://api.coinbase.com/api/v3/brokerage",
        balance = balance,
        holdings = holdings,
        quoteProduct = quoteProduct,
        quote = quote,
        authSucceeded = balance != null,
        message = if (balance != null) {
            "Mock auth succeeded - code logic verified"
        } else {
            "Mock auth failed (401 mode enabled)"
        },
    )
}
