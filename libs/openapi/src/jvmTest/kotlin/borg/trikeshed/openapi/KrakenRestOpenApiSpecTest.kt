package borg.trikeshed.openapi

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class KrakenRestOpenApiSpecTest {
    @Test
    fun specFileExistsAndCoversKrakenRestSurface() {
        val spec = File("../krak/rest-api/openapi/kraken.openapi.yaml")
        assertTrue(spec.exists(), "Expected ${spec.path} to exist")

        val text = spec.readText()
        assertTrue(text.contains("openapi: 3.1.0"), "Expected OpenAPI 3.1.0")
        assertTrue(text.contains("title: Kraken Spot REST API"), "Expected Kraken Spot REST API title")
        assertTrue(text.contains("https://api.kraken.com"), "Expected Kraken API server URL")

        listOf(
            "/0/public/Time",
            "/0/public/SystemStatus",
            "/0/public/AssetPairs",
            "/0/private/AddOrder",
            "/0/private/Balance",
            "/0/private/CancelAll",
            "/0/private/Withdraw",
        ).forEach { path ->
            assertTrue(text.contains(path), "Missing documented path: $path")
        }

        listOf("apiKeyAuth", "apiSignAuth").forEach { scheme ->
            assertTrue(text.contains(scheme), "Missing security scheme: $scheme")
        }
    }
}
