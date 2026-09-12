package borg.trikeshed.userspace

import borg.trikeshed.userspace.nio.ByteBuffer
import borg.trikeshed.userspace.nio.channels.SocketDomain
import borg.trikeshed.userspace.nio.channels.SocketType
import borg.trikeshed.userspace.nio.channels.sockaddrIpv4
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Socket lifecycle as SQEs through the common facade: SOCKET -> BIND -> LISTEN ->
 * CLOSE, every step settled by exactly one CQE. Loopback, ephemeral port.
 */
class SocketLifecycleSqeTest {

    @Test
    fun socketBindListenCloseSettlesCqes() = runTest {
        val backend = openJvmEmulatedChannelBackend()
        val facade = FunctionalUringFacade(16, backend)
        try {
            facade.enqueue(UringOp.Submissions.socket(
                SocketDomain.AF_INET.posix, SocketType.SOCK_STREAM.mask, 0, userData = 1L))
            facade.submit()
            val sockFd = facade.wait(1).single { it.userData == 1L }.res
            assertTrue(sockFd > 0, "SOCKET res=$sockFd")

            val addr = sockaddrIpv4(byteArrayOf(127, 0, 0, 1), 0)
            facade.enqueue(UringSubmission(UringOp.BIND, sockFd, 0, addr.size, 0,
                userData = 2L, buffer = ByteBuffer(addr)))
            facade.submit()
            val bindRes = facade.wait(1).single { it.userData == 2L }.res
            assertEquals(0, bindRes, "BIND res=$bindRes")

            facade.enqueue(UringSubmission(UringOp.LISTEN, sockFd, 0, 16, 0, userData = 3L))
            facade.submit()
            val listenRes = facade.wait(1).single { it.userData == 3L }.res
            assertEquals(0, listenRes, "LISTEN res=$listenRes")

            facade.enqueue(UringSubmission(UringOp.CLOSE, sockFd, 0, 0, 0, userData = 4L))
            facade.submit()
            val closeRes = facade.wait(1).single { it.userData == 4L }.res
            assertEquals(0, closeRes, "CLOSE res=$closeRes")
        } finally {
            facade.drain()
        }
    }
}
