package borg.trikeshed.pi

import borg.trikeshed.htx.HtxTransportProtocol
import borg.trikeshed.htx.parseHtxRequest
import borg.trikeshed.lib.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class GraalPiLoopbackTest {
    @Test fun acceptedEndpointsRemainLoopbackInTheTransportParser() {
        val cases = _l[
            Triple("http://127.0.0.1", "127.0.0.1", 80),
            Triple("http://127.0.0.1:1/v1", "127.0.0.1", 1),
            Triple("http://127.0.0.1:65535/v1/", "127.0.0.1", 65535),
            Triple("https://[::1]:443/v1", "[::1]", 443),
            Triple("HTTPS://[::1]/v1", "[::1]", 443),
        ]
        for ((url, host, port) in cases) {
            assertEquals(url, noAuthLoopbackBaseUrl(url))
            val target = parseHtxRequest(url.trimEnd('/') + "/chat/completions").target
            assertEquals(host, target.host, url)
            assertEquals(port, target.port, url)
            assertEquals(if (url.startsWith("https", ignoreCase = true)) HtxTransportProtocol.HTTPS
                else HtxTransportProtocol.HTTP, target.transportProtocol, url)
        }
    }

    @Test fun malformedOrNonLoopbackEndpointsAreRejected() {
        val invalid = _l[
            null, "", "  ", "/v1", "http:127.0.0.1", "ftp://127.0.0.1/v1",
            "http://localhost/v1", "http://127.0.0.2/v1", "http://127.1/v1",
            "http://2130706433/v1", "http://0177.0.0.1/v1", "http://[::ffff:127.0.0.1]/v1",
            "http://[::1%25lo]/v1", "http://127.0.0.1.evil/v1", "http://127.0.0.1@evil/v1",
            "http://evil@127.0.0.1/v1", "http://127.0.0.1%40evil/v1", "http://%31%32%37.0.0.1/v1",
            "http://127.0.0.1\\@evil/v1", "http:///127.0.0.1/v1", "http://127.0.0.1/v1?x=1",
            "http://127.0.0.1/v1#part", "http://127.0.0.1:0/v1", "http://127.0.0.1:65536/v1",
            "http://127.0.0.1:/v1", "http://127.0.0.1:abc/v1", "http://127.0.0.1%0d%0aevil/v1",
            "http://127.0.0.1/%zz", "http://127.0.0.1/line\nbreak",
        ]
        for (url in invalid) assertFailsWith<IllegalArgumentException>("Must reject $url") {
            noAuthLoopbackBaseUrl(url)
        }
    }
}
