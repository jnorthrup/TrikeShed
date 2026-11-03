package borg.trikeshed.graal.subvm

import borg.trikeshed.lib.*
import borg.trikeshed.job.*
import borg.trikeshed.userspace.nio.file.spi.*
import java.nio.ByteBuffer
import java.nio.file.StandardOpenOption.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.*

class TrikeShedGraalVfsChannelsTest {
    @Test fun ambiguousPublicationInvalidatesOpenChannelReads() {
        val backing = InMemoryFileOperations(cwd = "/")
        val fail = AtomicBoolean(false)
        val files = object : FileOperations by backing {
            override fun writeAtomically(filename: String, bytes: ByteArray) {
                backing.writeAtomically(filename, bytes)
                if (fail.get() && filename.endsWith("/live.manifest")) error("publication outcome unknown")
            }
        }
        val vfs = TrikeShedGraalVfs(fileOps = files)
        val path = vfs.parsePath("/workspace/data")
        vfs.put("/workspace/data", "old".encodeToByteArray())
        val open = vfs.newByteChannel(path, _s[READ])
        try {
            fail.set(true)
            assertFailsWith<IllegalStateException> { vfs.put("/workspace/data", "new".encodeToByteArray()) }
            assertContains(assertFailsWith<IllegalStateException> { vfs.fetch("/workspace/data") }.message.orEmpty(), "uncertain")
            assertContains(assertFailsWith<IllegalStateException> { open.read(ByteBuffer.allocate(3)) }.message.orEmpty(), "uncertain")
        } finally { open.close() }
    }

    @Test fun hostPutCapturesBytesOnceForStorageAndOpenHandles() {
        for (batch in _l[false, true]) {
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val armed = AtomicBoolean(false)
            val cas = object : CasStore() {
                override fun put(bytes: ByteArray): ContentId {
                    if (armed.get() && bytes.contentEquals("first".encodeToByteArray())) {
                        entered.countDown()
                        check(release.await(20, TimeUnit.SECONDS))
                    }
                    return super.put(bytes)
                }
            }
            val vfs = TrikeShedGraalVfs(cas = cas)
            val path = vfs.parsePath("/workspace/data")
            vfs.put("/workspace/data", "old".encodeToByteArray())
            val open = vfs.newByteChannel(path, _s[READ])
            val offered = "first".encodeToByteArray()
            val failure = AtomicReference<Throwable?>()
            val writer = Thread {
                try {
                    if (batch) vfs.put(s_["/workspace/data" j offered])
                    else vfs.put("/workspace/data", offered)
                } catch (caught: Throwable) { failure.set(caught) }
            }
            try {
                armed.set(true); writer.start()
                assertTrue(entered.await(20, TimeUnit.SECONDS), "CAS publication did not start")
                "later".encodeToByteArray().copyInto(offered)
                release.countDown(); writer.join(20_000)
                assertFalse(writer.isAlive, "publication did not finish")
                failure.get()?.let { throw it }
                assertEquals("first", assertNotNull(vfs.fetch("/workspace/data")).decodeToString())
                val observed = ByteBuffer.allocate(5)
                assertEquals(5, open.read(observed))
                assertEquals("first", observed.array().decodeToString())
            } finally { release.countDown(); writer.join(20_000); open.close() }
        }
    }

    @Test fun concurrentAppendsUseTheCurrentSharedEnd() {
        val vfs = TrikeShedGraalVfs()
        val path = vfs.parsePath("/workspace/data")
        vfs.put("/workspace/data", ByteArray(0))
        val first = vfs.newByteChannel(path, _s[APPEND])
        val second = vfs.newByteChannel(path, _s[APPEND])
        val begin = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        fun writer(channel: java.nio.channels.SeekableByteChannel, byte: Byte) = Thread {
            try {
                begin.await()
                repeat(50) { channel.write(ByteBuffer.wrap(_a[byte])) }
            } catch (caught: Throwable) { failure.compareAndSet(null, caught) }
        }
        val a = writer(first, 'a'.code.toByte())
        val b = writer(second, 'b'.code.toByte())
        try {
            a.start(); b.start(); begin.countDown(); a.join(20_000); b.join(20_000)
            assertFalse(a.isAlive || b.isAlive, "writers did not finish")
            failure.get()?.let { throw it }
            val bytes = assertNotNull(vfs.fetch("/workspace/data"))
            assertEquals(100, bytes.size)
            assertEquals(50, bytes.count { it == 'a'.code.toByte() })
            assertEquals(50, bytes.count { it == 'b'.code.toByte() })
        } finally { begin.countDown(); a.join(20_000); b.join(20_000); first.close(); second.close() }
    }

    @Test fun handlesShareWritesAppendAndTruncation() {
        val vfs = TrikeShedGraalVfs()
        val path = vfs.parsePath("/workspace/data")
        vfs.put("/workspace/data", "abcd".encodeToByteArray())
        val first = vfs.newByteChannel(path, _s[WRITE])
        val second = vfs.newByteChannel(path, _s[READ, WRITE])
        val append = vfs.newByteChannel(path, _s[APPEND])
        try {
            first.position(1); first.write(ByteBuffer.wrap("X".encodeToByteArray()))
            second.position(2); second.write(ByteBuffer.wrap("Y".encodeToByteArray()))
            append.write(ByteBuffer.wrap("!".encodeToByteArray()))
            val bytes = ByteBuffer.allocate(5)
            second.position(0); assertEquals(5, second.read(bytes))
            assertEquals("aXYd!", bytes.array().decodeToString())
            first.truncate(2)
            assertEquals(2L, second.size())
            append.write(ByteBuffer.wrap("?".encodeToByteArray()))
            assertEquals("aX?", vfs.fetch("/workspace/data")!!.decodeToString())
            vfs.put("/workspace/data", "host".encodeToByteArray())
            val host = ByteBuffer.allocate(4)
            second.position(0); assertEquals(4, second.read(host))
            assertEquals("host", host.array().decodeToString())
        } finally { first.close(); second.close(); append.close() }
    }

    @Test fun unlinkDetachesOldHandlesFromARecreatedPath() {
        val vfs = TrikeShedGraalVfs()
        val path = vfs.parsePath("/workspace/data")
        vfs.put("/workspace/data", "old".encodeToByteArray())
        val old = vfs.newByteChannel(path, _s[READ, WRITE])
        try {
            vfs.delete(path)
            vfs.put("/workspace/data", "new".encodeToByteArray())
            old.position(0); old.write(ByteBuffer.wrap("OLD".encodeToByteArray()))
            assertEquals("new", vfs.fetch("/workspace/data")!!.decodeToString())
            val bytes = ByteBuffer.allocate(3)
            old.position(0); assertEquals(3, old.read(bytes))
            assertEquals("OLD", bytes.array().decodeToString())
        } finally { old.close() }
    }
}
