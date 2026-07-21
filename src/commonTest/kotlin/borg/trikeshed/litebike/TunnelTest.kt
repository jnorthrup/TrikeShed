package borg.trikeshed.litebike

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.emptyFlow

class TunnelTest {
    @Test
    fun testInterfaces() {
        val p = object : Protocol {
            override val id: UByte = 1u
        }
        val t = object : Tunnel {
            override val id: String = "t1"
            override val protocol: Protocol = p
            override val remoteHost: String = "localhost"
            override val remotePort: Int = 8080
            override suspend fun connect() {}
            override suspend fun close() {}
            override fun read() = emptyFlow<ByteArray>()
            override suspend fun write(data: ByteArray) {}
        }
        assertEquals("t1", t.id)
        assertEquals(1u, t.protocol.id)
    }
}
