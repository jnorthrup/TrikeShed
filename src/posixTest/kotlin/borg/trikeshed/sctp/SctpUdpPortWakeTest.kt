@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package borg.trikeshed.sctp

import borg.trikeshed.lib.*
import borg.trikeshed.userspace.nio.channels.endpoint
import borg.trikeshed.userspace.nio.channels.parseWhole
import borg.trikeshed.userspace.nio.channels.spi.PosixChannelOperations
import borg.trikeshed.userspace.nio.channels.spi.PosixReactorOperations
import borg.trikeshed.userspace.nio.channels.spi.ReactorOperations
import borg.trikeshed.userspace.nio.channels.spi.ReactorSignal
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.time.Duration

/** The POSIX reactor, noting what each of its waits is cancelled with. */
class NotingReactor(val inner: PosixReactorOperations = PosixReactorOperations()) : ReactorOperations by inner {
    val causes = ArrayList<Throwable>()
    override suspend fun poll(timeout: Duration): List<ReactorSignal> =
        try { inner.poll(timeout) } catch (e: CancellationException) { causes += e; throw e }
}

class SctpUdpPortWakeTest {
    val secret = "sctp over udp".encodeToByteArray()

    val nobody = object : SctpUpper {
        override fun associated(tcb: SctpTcb) {}
        override fun message(tcb: SctpTcb, ppid: Ppid, bytes: ByteArray, offset: Int, length: Int) {}
        override fun closed(tcb: SctpTcb) {}
    }

    /**
     * Each wake that ends a reactor wait cancels it with the one exception: a Kotlin/Native Throwable captures its
     * stack trace when made, and a wake is the hottest path of the server.
     */
    @Test
    fun wakesCancelTheReactorWaitWithOneSharedException() = runBlocking {
        val reactor = NotingReactor()
        val port = SctpUdpPort(PosixChannelOperations(), reactor, parseWhole("127.0.0.1:39925") { endpoint() }!!, nobody, secret).open()
        for (i in (1..3).toSeries().view) {
            val waiting = launch { port.waitWake(Long.MAX_VALUE) }
            yield()
            port.kick.trySend(Unit)
            waiting.join()
        }
        port.stop()
        port.close()
        assertEquals(3, reactor.causes.size)
        assertSame(reactor.causes[0], reactor.causes[1])
        assertSame(reactor.causes[1], reactor.causes[2])
    }
}
