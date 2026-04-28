package borg.trikeshed.dreamer

interface ApiClient {
    suspend fun placeBuy(symbol: String, quantityStr: String): Any?
    suspend fun placeSell(symbol: String, quantityStr: String): Any?
    suspend fun getBalance(): Double?
    suspend fun getHoldings(): List<Any>?
    suspend fun getQuotes(assetCodes: List<String>): Map<String, Double>?
}
