package borg.trikeshed.userspace.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class ChannelsNamingTest {
    @Test
    fun channelsOpensChannel() {
        val channels = object : Channels {
            override fun open(addr: String): Channel = object : Channel {
                override fun channelType(): String = "stub"
                override fun isConnected(): Boolean = false
                override fun metadata(): ChannelMetadata = ChannelMetadata(remoteAddr = addr)
                override fun read(buf: ByteArray): Int = 0
                override fun write(buf: ByteArray): Int = 0
            }

            override fun providerName(): String = "stub"
        }

        val channel = channels.open("127.0.0.1:80")
        assertEquals("127.0.0.1:80", channel.metadata()?.remoteAddr)
        assertFalse(channel.isConnected())
    }
}
