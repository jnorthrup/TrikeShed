
package borg.trikeshed.openapi

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class GuideContourSpecTest {
    @Test
    fun krakenGlobalIntroContourIncludesDocumentedSurface() {
        assertGuideSpec(
            file = File("../krak/global-intro/openapi/kraken.openapi.yaml"),
            expectedTitle = "Kraken APIs",
            expectedServer = "https://docs.kraken.com",
            expectedPaths = listOf(
                "/api/docs/guides/global-intro",
                "/api/docs/guides/spot-rest-intro",
                "/api/docs/guides/spot-ws-intro",
                "/api/docs/guides/fix-intro",
                "/api/docs/guides/embed-rest-auth",
                "/api/docs/guides/embed-first-trade",
            ),
            expectedSections = listOf(
                "Introduction",
                "Direct Trading APIs",
                "Embed API (B2B / B2B2C)",
                "Choosing an API",
                "Summary of product versus exchange / API",
                "Futures and Spot Trading",
                "IP Whitelisting",
                "Colocation Access",
                "Endpoint URLs",
                "FAQ and Support",
                "Notices",
            ),
            extraFragments = listOf(
                "REST API",
                "Websocket API",
                "FIX API",
                "Embed Authentication Guide",
                "Your First Trade",
                "colo-london.vip-ws.kraken.com",
                "colo-london.vip.futures.kraken.com",
                "marketdata@kraken.com",
            ),
        )
    }

    @Test
    fun coinMarketCapEndpointOverviewContourIncludesDocumentedSurface() {
        assertGuideSpec(
            file = File("../cmc/endpoint-overview/openapi/coinmarketcap.openapi.yaml"),
            expectedTitle = "CoinMarketCap API Documentation",
            expectedServer = "https://coinmarketcap.com",
            expectedPaths = listOf(
                "/api/documentation/pro-api-reference/endpoint-overview",
                "/api/documentation/pro-api-reference/cryptocurrency",
                "/api/documentation/pro-api-reference/exchange",
                "/api/documentation/pro-api-reference/global-metrics",
                "/api/documentation/pro-api-reference/content",
                "/api/documentation/pro-api-reference/community",
                "/api/documentation/pro-api-reference/cmc-index",
                "/api/documentation/pro-api-reference/crypto-others",
                "/api/documentation/pro-api-reference/token",
                "/api/documentation/pro-api-reference/platform",
                "/api/documentation/pro-api-reference/holder",
                "/api/documentation/pro-api-reference/ohlcv",
                "/api/documentation/pro-api-reference/tools",
                "/api/documentation/pro-api-reference/deprecated",
            ),
            expectedSections = listOf(
                "Which CoinMarketCap API Endpoint Should I Use?",
                "Start with your goal",
                "Quick rules that save time",
                "Browse all API families",
                "Market Data",
                "DEX Data",
                "Utilities",
                "Legacy",
            ),
            extraFragments = listOf(
                "Use listings endpoints when you want sorted, paginated lists.",
                "Use quotes, info, and market-pairs endpoints when you already know which assets or exchanges you care about.",
                "Use */latest for current market data and */historical for time-series data.",
                "Use CoinMarketCap IDs when possible; they are more stable than symbols.",
                "Quotes, listings, OHLCV, market pairs, trending, categories, airdrops, and price performance stats.",
                "Token lookup, batch queries, price, liquidity, pools, transactions, trending lists, new tokens, meme tokens, gainers/losers, and security analysis.",
            ),
        )
    }

    private fun assertGuideSpec(
        file: File,
        expectedTitle: String,
        expectedServer: String,
        expectedPaths: List<String>,
        expectedSections: List<String>,
        extraFragments: List<String>,
    ) {
        assertTrue(file.exists(), "Expected ${file.path} to exist")
        val text = file.readText()

        assertTrue(text.contains("openapi: 3.1.0"), "Expected ${file.path} to declare OpenAPI 3.1.0")
        assertTrue(text.contains("title: $expectedTitle"), "Expected ${file.path} to contain title $expectedTitle")
        assertTrue(text.contains("url: $expectedServer"), "Expected ${file.path} to point at $expectedServer")

        expectedPaths.forEach { path ->
            assertTrue(text.contains(path), "Missing path in ${file.path}: $path")
        }

        expectedSections.forEach { section ->
            assertTrue(text.contains(section), "Missing section in ${file.path}: $section")
        }

        extraFragments.forEach { fragment ->
            assertTrue(text.contains(fragment), "Missing contour fragment in ${file.path}: $fragment")
        }
    }
}
