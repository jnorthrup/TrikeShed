package borg.trikeshed.userspace.nio.channels.spi

import borg.trikeshed.userspace.reactor.Interest
import kotlinx.cinterop.*
import kotlinx.coroutines.runBlocking
import platform.posix.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalForeignApi::class)
class KqueueReactorOperationsTest {
    @Test fun readinessIsLevelTriggeredAndDeregisterSilencesIt() = runBlocking {
        val reactor = KqueueReactorOperations()
        memScoped {
            val pair = allocArray<IntVar>(2)
            check(socketpair(AF_UNIX, SOCK_STREAM, 0, pair) == 0)
            val a = pair[0]; val b = pair[1]
            try {
                reactor.register(a, setOf(Interest.READ), 7L)
                assertTrue(reactor.poll(20.milliseconds).isEmpty())
                val byte = alloc<ByteVar>().apply { value = 1 }
                assertEquals(1L, write(b, byte.ptr, 1u).toLong())
                val first = reactor.poll(1000.milliseconds).single()
                assertEquals(a, first.fd); assertEquals(7L, first.userData); assertTrue(Interest.READ in first.ready)
                // Level-triggered: still ready until read.
                assertEquals(a, reactor.poll(1000.milliseconds).single().fd)
                assertEquals(1L, read(a, byte.ptr, 1u).toLong())
                assertTrue(reactor.poll(20.milliseconds).isEmpty())
                assertEquals(1L, write(b, byte.ptr, 1u).toLong())
                reactor.deregister(a)
                assertTrue(reactor.poll(20.milliseconds).isEmpty())
                // Peer close: ERROR with the registered interest.
                reactor.register(a, setOf(Interest.READ), 9L)
                close(b)
                val eof = reactor.poll(1000.milliseconds).single()
                assertTrue(Interest.ERROR in eof.ready && Interest.READ in eof.ready)
            } finally { close(a) }
        }
    }
}
