package borg.trikeshed.userspace.nio.channels

import borg.trikeshed.lib.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InetGrammarTest {
    @Test fun portsAndRanges() {
        assertEquals(10000..48999, parseWhole("10000-48999") { portRange() })
        assertEquals(80..80, parseWhole(" 80 ") { portRange() })
        for (text in s_["65536", "200-100", "10-"].view) assertNull(parseWhole(text) { portRange() }, text)
    }

    @Test fun addressesRangesEndpointsAndLists() {
        val address = parseWhole("192.168.50.201") { ipv4() }!!
        for ((at, octet) in s_[192, 168, 50, 201].view.withIndex()) assertEquals(octet, address[at].toInt() and 0xFF)
        for (text in s_["192.168.50.256", "192.168.50", "192.168.50.1."].view) assertNull(parseWhole(text) { ipv4() }, text)

        val aliases = parseWhole("192.168.50.201-208") { ipv4Range() }!!
        assertEquals(8, aliases.size)
        for ((at, last) in (aliases α { it[3].toInt() and 0xFF }).view.withIndex()) assertEquals(201 + at, last)

        assertEquals(3200, parseWhole("192.168.50.44:3200") { endpoint() }!!.b)
        val targets = parseWhole("127.0.0.1:3000,127.0.0.1:3001") { list { endpoint() } }!!
        for ((at, port) in (targets α { it.b }).view.withIndex()) assertEquals(3000 + at, port)
        assertNull(parseWhole("127.0.0.1:3000,") { list { endpoint() } })
    }

    @Test fun ipv6SpellingsMeetAndSctpEndpointsShareOnePort() {
        val same = s_[
            "::1" j "0:0:0:0:0:0:0:1",
            "2001:db8::8:800:200c:417a" j "2001:db8:0:0:8:800:200c:417a",
            "::ffff:192.168.50.1" j "::ffff:c0a8:3201",
            "fe80::" j "fe80:0:0:0:0:0:0:0",
        ]
        for ((short, full) in same.view) {
            val a = parseWhole(short) { ipv6() }!!
            assertEquals(16, a.size, short)
            assertTrue(a contentEquals parseWhole(full) { ipv6() }!!, short)
        }
        for (text in s_["1::2::3", "1:2:3:4:5:6:7:8:9", "12345::", "1:2:3", ":1"].view) assertNull(parseWhole(text) { ipv6() }, text)

        assertEquals(4, parseWhole("10.0.0.1") { inet() }!!.size)
        assertEquals(16, parseWhole("::1") { inet() }!!.size)
        assertEquals(443, parseWhole("[::1]:443") { endpoint() }!!.b)
        assertNull(parseWhole("::1:443") { endpoint() })

        val association = parseWhole("10.0.0.1,[2001:db8::1],10.0.1.1:9899") { sctpEndpoint() }!!
        assertEquals(9899, association.b)
        for ((at, width) in (association.a α { it.size }).view.withIndex()) assertEquals(s_[4, 16, 4][at], width)
    }

    @Test fun sourcePortsRoundRobinPerAddress() {
        val ports = SourcePorts(SourcePorts.parse("100-102"))
        val a = parseWhole("10.0.0.1") { ipv4() }!!
        val b = parseWhole("10.0.0.2") { ipv4() }!!
        for (expected in s_[100, 101, 102, 100].view) assertEquals(expected, ports.next(a))
        assertEquals(100, ports.next(b))
    }
}
