package borg.trikeshed.userspace.network

import borg.trikeshed.lib.ByteSeries
import borg.trikeshed.lib.asString
import borg.trikeshed.userspace.ByteRegion
import borg.trikeshed.userspace.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class ChannelsNamingTest {
    @Test
    fun channelsOpensChannel() {
        var lastWrite = ""
        val channels = object : Channels {
            override fun open(addr: CharSequence): Channel = object : Channel {
                override fun channelType(): CharSequence = "stub"
                override fun isConnected(): Boolean = false
                override fun metadata(): ChannelMetadata = ChannelMetadata(remoteAddr = addr)
                override fun read(dst: ByteRegion): Int {
                    val src = "ok".encodeToByteArray()
                    for (i in src.indices) dst.put(i, src[i])
                    return src.size
                }
                override fun write(src: ByteSeries): Int {
                    lastWrite = src.asString().toString()
                    return src.rem
                }
            }

            override fun providerName(): CharSequence = "stub"
        }

        val channel = channels.open("127.0.0.1:80")
        val readBuffer = ByteBuffer.allocate(4)
        val readRegion = ByteRegion(readBuffer)
        val readCount = channel.read(readRegion)
        val writeCount = channel.write(ByteSeries("ping"))
        assertEquals("127.0.0.1:80", channel.metadata()?.remoteAddr)
        assertFalse(channel.isConnected())
        assertEquals(2, readCount)
        readBuffer.limit(readCount)
        assertEquals("ok", ByteRegion(readBuffer).asByteSeries().asString())
        assertEquals(4, writeCount)
        assertEquals("ping", lastWrite)
    }
}
