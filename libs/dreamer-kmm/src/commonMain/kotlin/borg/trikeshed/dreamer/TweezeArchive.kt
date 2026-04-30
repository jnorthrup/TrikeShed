package borg.trikeshed.dreamer

/**
 * Utility to curate trading pairs from raw binance symbols.
 */
class TweezeArchive {
    companion object {
        val QUOTE_ASSETS = listOf("USDT", "BUSD", "USDC", "TUSD", "FDUSD", "BTC", "ETH", "BNB", "TRY", "EUR", "RUB")

        /**
         * Parses a raw un-delimited symbol string (e.g. BTCUSDT) into a CuratedPair
         * using known quote assets to split the string.
         */
        fun parseSymbol(rawSymbol: String): Pair<String, String>? {
            for (quote in QUOTE_ASSETS) {
                if (rawSymbol.endsWith(quote) && rawSymbol != quote) {
                    val base = rawSymbol.substring(0, rawSymbol.length - quote.length)
                    return base to quote
                }
            }
            return null
        }

        /**
         * Formats a base/quote pair into the TRADE-COUNTER naming convention.
         */
        fun formatCurated(base: String, quote: String): String {
            return "TRADE-$base/COUNTER-$quote"
        }
    }
}
