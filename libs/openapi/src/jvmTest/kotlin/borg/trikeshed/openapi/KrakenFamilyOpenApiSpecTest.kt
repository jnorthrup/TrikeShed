package borg.trikeshed.openapi

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class KrakenFamilyOpenApiSpecTest {
    @Test
    fun custodySpecExistsAndCoversCustodySurface() {
        assertSpec(
            file = File("../krak/custody-api/openapi/kraken.openapi.yaml"),
            title = "Kraken Custody REST API",
            server = "https://api.kraken.com",
            paths = listOf(
                "/0/private/GetCustodyBalance",
                "/0/private/ListCustodyVaults",
                "/0/private/GetCustodyTransaction",
            ),
        )
    }

    @Test
    fun embedSpecExistsAndCoversEmbedSurface() {
        assertSpec(
            file = File("../krak/embed-api/openapi/kraken.openapi.yaml"),
            title = "Kraken Embed API",
            server = "https://nexus.kraken.com",
            paths = listOf(
                "/b2b/users",
                "/b2b/users/{user}",
                "/b2b/custom-orders",
            ),
        )
    }

    @Test
    fun embedV1SpecExistsAndCoversEmbedV1Surface() {
        assertSpec(
            file = File("../krak/embed-v1-api/openapi/kraken.openapi.yaml"),
            title = "Kraken Embed v1 API",
            server = "https://api.kraken.com",
            paths = listOf(
                "/0/private/CreateUser",
                "/0/private/GetUser",
                "/0/private/VerifyUser",
            ),
        )
    }

    @Test
    fun futuresSpecExistsAndCoversFuturesSurface() {
        assertSpec(
            file = File("../krak/futures-api/openapi/kraken.openapi.yaml"),
            title = "Kraken Futures REST API",
            server = "https://futures.kraken.com",
            paths = listOf(
                "/derivatives/api/v3/openorders",
                "/derivatives/api/v3/tickers",
                "/derivatives/api/v3/history",
                "/derivatives/api/v3/instruments",
            ),
        )
    }

   fun assertSpec(file: File, title: CharSequence, server: CharSequence, paths: List<CharSequence>) {
        assertTrue(file.exists(), "Expected ${file.path} to exist")

        val text = file.readText()
        assertTrue(text.contains("openapi: 3.1.0"), "Expected OpenAPI 3.1.0 in ${file.path}")
        assertTrue(text.contains("title: $title"), "Expected title $title in ${file.path}")
        assertTrue(text.contains(server), "Expected server $server in ${file.path}")

        paths.forEach { path ->
            assertTrue(text.contains(path), "Missing documented path $path in ${file.path}")
        }

        listOf("apiKeyAuth", "apiSignAuth").forEach { scheme ->
            assertTrue(text.contains(scheme), "Expected security scheme $scheme in ${file.path}")
        }
    }
}
