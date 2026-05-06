package borg.trikeshed.userspace.kernel

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ReactorTest {
    @Test
    fun simpleReactorCountsReadyOps() = runTest {
        val reactor = SimpleReactor()
        reactor.register(object : SelectableChannelOps {
            override suspend fun pollReadable(timeout: kotlin.time.Duration?) = true
            override suspend fun pollWritable(timeout: kotlin.time.Duration?) = false
            override fun tryRead(buf: ByteArray): Int = 0
            override fun tryWrite(buf: ByteArray): Int = 0
        })
        reactor.register(object : SelectableChannelOps {
            override suspend fun pollReadable(timeout: kotlin.time.Duration?) = false
            override suspend fun pollWritable(timeout: kotlin.time.Duration?) = true
            override fun tryRead(buf: ByteArray): Int = 0
            override fun tryWrite(buf: ByteArray): Int = 0
        })

        assertEquals(2, reactor.tick())
        assertEquals(2, reactor.channelCount())
    }
}
