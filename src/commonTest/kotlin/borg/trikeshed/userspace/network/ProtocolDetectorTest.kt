package borg.trikeshed.userspace.network

import borg.trikeshed.lib.ByteSeries
import kotlin.test.Test
import kotlin.test.assertEquals

class ProtocolDetectorTest {
    @Test
    fun protocolDetectorDetectsHttpFromByteSeries() {
        val detector = ProtocolDetector()

        detector.feed(ByteSeries("GET / HTTP/1.1\r\n"))

        assertEquals(Protocol.Http, detector.protocol())
    }
}
