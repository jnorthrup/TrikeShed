package borg.trikeshed.loom

import borg.trikeshed.lib.*
import kotlin.test.*

/** [domain_to_ascii_cow], [Url.Host.parse] and [validate_url] against the url and idna crates' answers in [IdnaVectors]. */
class LoomIdnaTest {
    fun answers(host: String): String {
        val parsed = try {
            "ok " + Url.Host.parse(host)
        } catch (refused: IllegalArgumentException) {
            "err"
        }
        return parsed + "\t" + answer(host.encodeToByteArray())
    }

    fun answer(bytes: ByteArray): String = domain_to_ascii_cow(bytes)?.let { "ok $it" } ?: "err"

    @Test
    fun hostsMatchTheCrates() {
        for ((host, expected) in IdnaVectors.hosts.view) assertEquals(expected, answers(host), host)
    }

    @Test
    fun punycodeLengthLimitsMatchTheCrates() {
        for ((host, expected) in IdnaVectors.limits.view) assertEquals(expected, answers(host), host.take(16))
    }

    @Test
    fun bytesThatAreNotUtf8MatchTheCrate() {
        for ((hex, expected) in IdnaVectors.bytes.view) assertEquals(expected, answer(hex.hexToByteArray()), hex)
    }

    @Test
    fun validateUrlMatchesTheUrlCrateOnIdnaHosts() {
        for ((input, expected) in IdnaVectors.urls.view) assertEquals(
            expected,
            listOf(false to false, true to false, true to true).joinToString(" | ") { (loopback, test) ->
                try {
                    "OK " + validate_url(input, loopback, test)
                } catch (failure: IllegalStateException) {
                    "ERR ${failure.message}"
                }
            },
            input,
        )
    }
}
