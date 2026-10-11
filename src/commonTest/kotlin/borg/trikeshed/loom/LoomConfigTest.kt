package borg.trikeshed.loom

import kotlin.test.*

/**
 * config.rs validate_url (cocaine-rats e4898291) against the url crate 2.5.8 it parses with, captured by a scratch crate
 * running the Rust function verbatim: each input under (allow_loopback_http, insecure_local_test_only) = (false, false),
 * (true, false), (true, true). IPv6 literals and IDNA hosts are the marked gaps and are not listed.
 */
class LoomConfigTest {
    val vectors = listOf(
        "http://127.0.0.1:8801/" to "ERR unsafe URL | OK http://127.0.0.1:8801/ | OK http://127.0.0.1:8801/",
        "http://127.0.0.1:8801" to "ERR unsafe URL | OK http://127.0.0.1:8801/ | OK http://127.0.0.1:8801/",
        "HTTP://127.0.0.1:8801/" to "ERR unsafe URL | OK http://127.0.0.1:8801/ | OK http://127.0.0.1:8801/",
        "http://127.1:8801/" to "ERR unsafe URL | OK http://127.0.0.1:8801/ | OK http://127.0.0.1:8801/",
        "http://0x7f.1/" to "ERR unsafe URL | OK http://127.0.0.1/ | OK http://127.0.0.1/",
        "http://2130706433/" to "ERR unsafe URL | OK http://127.0.0.1/ | OK http://127.0.0.1/",
        "http://127.0.0.1:80/" to "ERR unsafe URL | OK http://127.0.0.1/ | OK http://127.0.0.1/",
        "http://127.0.0.1:0080/" to "ERR unsafe URL | OK http://127.0.0.1/ | OK http://127.0.0.1/",
        "http://10.0.0.5/" to "ERR unsafe URL | ERR unsafe URL | OK http://10.0.0.5/",
        "http://192.168.1.2:9/" to "ERR unsafe URL | ERR unsafe URL | OK http://192.168.1.2:9/",
        "http://172.16.0.1/" to "ERR unsafe URL | ERR unsafe URL | OK http://172.16.0.1/",
        "http://172.32.0.1/" to "ERR unsafe URL | ERR unsafe URL | ERR unsafe URL",
        "http://8.8.8.8/" to "ERR unsafe URL | ERR unsafe URL | ERR unsafe URL",
        "http://localhost/" to "ERR unsafe URL | ERR unsafe URL | ERR unsafe URL",
        "https://localhost:443/" to "OK https://localhost/ | OK https://localhost/ | OK https://localhost/",
        "https://example.com" to "OK https://example.com/ | OK https://example.com/ | OK https://example.com/",
        "https://EXAMPLE.com:8443/" to "OK https://example.com:8443/ | OK https://example.com:8443/ | OK https://example.com:8443/",
        "https://example.com/x" to "ERR unsafe URL | ERR unsafe URL | ERR unsafe URL",
        "https://example.com/?" to "ERR unsafe URL | ERR unsafe URL | ERR unsafe URL",
        "https://example.com/#" to "ERR unsafe URL | ERR unsafe URL | ERR unsafe URL",
        "https://example.com/?a=b" to "ERR unsafe URL | ERR unsafe URL | ERR unsafe URL",
        "https://user@example.com/" to "ERR unsafe URL | ERR unsafe URL | ERR unsafe URL",
        "https://user:pw@example.com/" to "ERR unsafe URL | ERR unsafe URL | ERR unsafe URL",
        "https://:@example.com/" to "OK https://example.com/ | OK https://example.com/ | OK https://example.com/",
        "https://@example.com/" to "OK https://example.com/ | OK https://example.com/ | OK https://example.com/",
        "https://:pw@example.com/" to "ERR unsafe URL | ERR unsafe URL | ERR unsafe URL",
        "https://example.com/a/.." to "OK https://example.com/ | OK https://example.com/ | OK https://example.com/",
        "https://example.com/." to "OK https://example.com/ | OK https://example.com/ | OK https://example.com/",
        "https://example.com/./" to "OK https://example.com/ | OK https://example.com/ | OK https://example.com/",
        "https://example.com//" to "ERR unsafe URL | ERR unsafe URL | ERR unsafe URL",
        "https://example.com/%2e" to "OK https://example.com/ | OK https://example.com/ | OK https://example.com/",
        "https://example.com/a/%2E%2E" to "OK https://example.com/ | OK https://example.com/ | OK https://example.com/",
        "https:example.com/" to "OK https://example.com/ | OK https://example.com/ | OK https://example.com/",
        "https:///example.com/" to "OK https://example.com/ | OK https://example.com/ | OK https://example.com/",
        "https:\\\\example.com\\" to "OK https://example.com/ | OK https://example.com/ | OK https://example.com/",
        "  https://example.com/  " to "OK https://example.com/ | OK https://example.com/ | OK https://example.com/",
        "ftp://example.com/" to "ERR unsafe URL | ERR unsafe URL | ERR unsafe URL",
        "file:///etc/passwd" to "ERR unsafe URL | ERR unsafe URL | ERR unsafe URL",
        "example.com" to "ERR invalid URL | ERR invalid URL | ERR invalid URL",
        "" to "ERR invalid URL | ERR invalid URL | ERR invalid URL",
        "https://" to "ERR invalid URL | ERR invalid URL | ERR invalid URL",
        "https://:443/" to "ERR invalid URL | ERR invalid URL | ERR invalid URL",
        "https://example.com:65535/" to "OK https://example.com:65535/ | OK https://example.com:65535/ | OK https://example.com:65535/",
        "https://example.com:65536/" to "ERR invalid URL | ERR invalid URL | ERR invalid URL",
        "https://example.com:-1/" to "ERR invalid URL | ERR invalid URL | ERR invalid URL",
        "https://example.com:abc/" to "ERR invalid URL | ERR invalid URL | ERR invalid URL",
        "https://ex ample.com/" to "ERR invalid URL | ERR invalid URL | ERR invalid URL",
        "https://ex<ample.com/" to "ERR invalid URL | ERR invalid URL | ERR invalid URL",
        "https://1.2.3.4.5/" to "ERR invalid URL | ERR invalid URL | ERR invalid URL",
        "https://1.2.3/" to "OK https://1.2.0.3/ | OK https://1.2.0.3/ | OK https://1.2.0.3/",
        "https://256.0.0.1/" to "ERR invalid URL | ERR invalid URL | ERR invalid URL",
        "https://0x100000000/" to "ERR invalid URL | ERR invalid URL | ERR invalid URL",
        "https://09.1.1.1/" to "ERR invalid URL | ERR invalid URL | ERR invalid URL",
        "https://a.0x/" to "ERR invalid URL | ERR invalid URL | ERR invalid URL",
        "http://127.0.0.1./" to "ERR unsafe URL | OK http://127.0.0.1/ | OK http://127.0.0.1/",
        "http://127.0.0.1:8801/v1/" to "ERR unsafe URL | ERR unsafe URL | ERR unsafe URL",
        "https://a.b-c.d_e/" to "OK https://a.b-c.d_e/ | OK https://a.b-c.d_e/ | OK https://a.b-c.d_e/",
        "https://a..b/" to "OK https://a..b/ | OK https://a..b/ | OK https://a..b/",
        "https://example.com." to "OK https://example.com./ | OK https://example.com./ | OK https://example.com./",
        "https://.example.com/" to "OK https://.example.com/ | OK https://.example.com/ | OK https://.example.com/",
        "1http://x/" to "ERR invalid URL | ERR invalid URL | ERR invalid URL",
        "h t://x/" to "ERR invalid URL | ERR invalid URL | ERR invalid URL",
        "http://0177.0.0.1/" to "ERR unsafe URL | OK http://127.0.0.1/ | OK http://127.0.0.1/",
        "http://127.0.0.1:/" to "ERR unsafe URL | OK http://127.0.0.1/ | OK http://127.0.0.1/",
        "https://example.com:00443/" to "OK https://example.com/ | OK https://example.com/ | OK https://example.com/",
        "http://127.000.000.001/" to "ERR unsafe URL | OK http://127.0.0.1/ | OK http://127.0.0.1/",
        "https://a@b@c.com/" to "ERR unsafe URL | ERR unsafe URL | ERR unsafe URL",
        "https://example.com\\x" to "ERR unsafe URL | ERR unsafe URL | ERR unsafe URL",
        "https://" + "0".repeat(600) + "/" to "ERR URL size | ERR URL size | ERR URL size",
        "https://exa\tmple.com/" to "OK https://example.com/ | OK https://example.com/ | OK https://example.com/",
        "http://4294967295/" to "ERR unsafe URL | ERR unsafe URL | ERR unsafe URL",
        "http://4294967296/" to "ERR invalid URL | ERR invalid URL | ERR invalid URL",
        "https://1.2.3.0x100/" to "ERR invalid URL | ERR invalid URL | ERR invalid URL",
        "https://1.2.65535/" to "OK https://1.2.255.255/ | OK https://1.2.255.255/ | OK https://1.2.255.255/",
        "https://1.2.65536/" to "ERR invalid URL | ERR invalid URL | ERR invalid URL",
        "http://0x7F000001:8801/" to "ERR unsafe URL | OK http://127.0.0.1:8801/ | OK http://127.0.0.1:8801/",
        "https://EXAMPLE.COM./" to "OK https://example.com./ | OK https://example.com./ | OK https://example.com./",
    )

    @Test
    fun validateUrlMatchesTheUrlCrate() {
        for ((input, expected) in vectors) assertEquals(
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
