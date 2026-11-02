package borg.trikeshed.userspace

import borg.trikeshed.userspace.nio.ByteBuffer
import borg.trikeshed.userspace.nio.IOException
import borg.trikeshed.userspace.nio.channels.Pipe
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class UringPipeRaceTest {
    @Test
    fun closing_opposite_endpoint_during_transfer_is_never_lost() {
        val executor = Executors.newFixedThreadPool(2)
        try {
            repeat(128) {
                val pipe = Pipe.open()
                try {
                    val bytes = ByteBuffer(ByteArray(65536) { 7 })
                    assertEquals(65536, pipe.sink().write(bytes))
                    val start = CountDownLatch(1)
                    val received = ByteBuffer(65536)
                    val read = executor.submit<Int> { start.await(); pipe.source().read(received) }
                    val close = executor.submit { start.await(); pipe.sink().close() }
                    start.countDown()
                    assertEquals(65536, read.get(2, TimeUnit.SECONDS))
                    close.get(2, TimeUnit.SECONDS)
                    assertEquals(7, received.get(0).toInt())
                    assertEquals(7, received.get(65535).toInt())
                    assertEquals(-1, pipe.source().read(ByteBuffer(1)), "sink close must become EOF after transferred bytes drain")
                } finally {
                    pipe.sink().close()
                    pipe.source().close()
                }

                val pipe2 = Pipe.open()
                try {
                    val start = CountDownLatch(1)
                    val write = executor.submit<Int> {
                        start.await()
                        try { pipe2.sink().write(ByteBuffer(ByteArray(65536))) }
                        catch (_: IOException) { -1 }
                    }
                    val close = executor.submit { start.await(); pipe2.source().close() }
                    start.countDown()
                    write.get(2, TimeUnit.SECONDS)
                    close.get(2, TimeUnit.SECONDS)
                    val remaining = ByteBuffer(byteArrayOf(1))
                    assertFailsWith<IOException> { pipe2.sink().write(remaining) }
                    assertEquals(0, remaining.position(), "write after source close must not consume the caller buffer")
                } finally {
                    pipe2.sink().close()
                    pipe2.source().close()
                }
            }
        } finally {
            executor.shutdownNow()
            executor.awaitTermination(2, TimeUnit.SECONDS)
        }
    }
}
