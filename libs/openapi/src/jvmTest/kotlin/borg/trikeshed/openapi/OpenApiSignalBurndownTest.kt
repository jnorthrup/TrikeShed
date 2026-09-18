package borg.trikeshed.openapi

import java.io.File
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class OpenApiSignalBurndownTest {
    @Test
    fun fansOutContourDocumentsAsSignalActions() = runTest {
        val calls = listOf(
            OpenApiCall("kraken", File("../krak/global-intro/openapi/kraken.openapi.yaml").readText()),
            OpenApiCall("cmc", File("../cmc/endpoint-overview/openapi/coinmarketcap.openapi.yaml").readText()),
        )

        val actions = speculativeSignalBurndown(
            calls = calls,
            parallelism = 2,
            parser = { it },
            signalAction = { parsed ->
                val text = parsed.rawText
                val title = text.lineSequence()
                    .first { it.trimStart().startsWith("title:") }
                    .substringAfter(":")
                    .trim()
                val sectionCount = text.lineSequence().count { it.trimStart().startsWith("- heading:") }
                val signal = if (text.contains("x-page-contour:")) "contoured" else "missing"
                ContourSignal(title = title, sectionCount = sectionCount, signal = signal)
            },
        )

        assertEquals(2, actions.size)
        assertTrue(actions.all { it.output.signal == "contoured" })
        assertTrue(actions.any { it.callId == "kraken" && it.output.title == "Kraken APIs" && it.output.sectionCount == 11 })
        assertTrue(actions.any { it.callId == "cmc" && it.output.title == "CoinMarketCap API Documentation" && it.output.sectionCount == 8 })
    }

    @Test
    fun failsFastWhenContourSignalActionThrows() = runTest {
        val failure = assertFailsWith<OpenApiCallFailure> {
            speculativeSignalBurndown(
                calls = listOf(OpenApiCall("kraken", "signal me")),
                parallelism = 1,
                parser = { it },
                signalAction = { parsed ->
                    error("boom:${parsed.callId}")
                },
            )
        }

        assertEquals("kraken", failure.callId)
        assertTrue(failure.cause is IllegalStateException)
        assertTrue(failure.message?.contains("kraken") == true)
    }
}

data class ContourSignal(
    val title: String,
    val sectionCount: Int,
    val signal: String,
)
