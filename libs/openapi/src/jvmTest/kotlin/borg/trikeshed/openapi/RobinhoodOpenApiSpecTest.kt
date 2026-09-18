package borg.trikeshed.openapi

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class RobinhoodOpenApiSpecTest {
    @Test
    fun specFileExistsAndCoversDocumentedSurface() {
        val spec = File("../rhood/robinhood.openapi.yaml")
        assertTrue(spec.exists(), "Expected ${spec.path} to exist")

        val text = spec.readText()
        assertTrue(text.contains("openapi: 3.1.0"))
        assertTrue(text.contains("title: Robinhood Crypto Trading API"))
        assertTrue(text.contains("https://trading.robinhood.com"))

        listOf(
            "/api/v1/crypto/trading/accounts/",
            "/api/v1/crypto/trading/orders/",
            "/api/v1/crypto/trading/orders/{order_id}/cancel/",
            "/api/v2/crypto/trading/accounts/",
            "/api/v2/crypto/trading/orders/",
            "/api/v2/crypto/trading/orders/{id}/cancel/",
        ).forEach { path ->
            assertTrue(text.contains(path), "Missing documented path: $path")
        }

        listOf("x-api-key", "x-signature", "x-timestamp").forEach { scheme ->
            assertTrue(text.contains(scheme), "Missing security scheme: $scheme")
        }
    }
}
