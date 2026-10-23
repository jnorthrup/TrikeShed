package borg.trikeshed.btrfs

import borg.trikeshed.userspace.nio.ByteBuffer
import borg.trikeshed.userspace.nio.channels.SocketChannel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BtrfsMountControlTest {
    private val token = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"

    @Test
    fun authenticated_loopback_commands_survive_invalid_requests_and_drain_admitted_work() = runBlocking<Unit> {
        withTimeout(20000) {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val commands = mutableListOf<List<String>>()
            val control = BtrfsMountControl.open(this, token) { command ->
                commands += command
                if (command.first() == "add") { entered.complete(Unit); release.await() }
                "accepted ${command.joinToString(" ")}"
            }
            try {
                assertTrue(control.address.startsWith("127.0.0.1:"))
                assertFailsWith<IllegalStateException> { BtrfsMountControl.request(control.address, "f".repeat(64), listOf("info")) }
                assertFailsWith<IllegalArgumentException> { BtrfsMountControl.request("10.0.0.1:1234", token, listOf("info")) }
                assertFailsWith<IllegalArgumentException> { BtrfsMountControl.request(control.address, token, listOf("rm", "/")) }
                assertEquals("accepted info", BtrfsMountControl.request(control.address, token, listOf("info")))

                // A malformed length is rejected before allocation, and the listener remains usable.
                val invalid = SocketChannel.openWithRemote(control.address)
                try {
                    invalid.write(ByteBuffer(4).putInt(Int.MAX_VALUE).flip())
                } finally { invalid.close() }
                assertEquals("accepted compression LZ4",
                    BtrfsMountControl.request(control.address, token, listOf("compression", "LZ4")))

                val request = async { BtrfsMountControl.request(control.address, token, listOf("add", "128")) }
                entered.await()
                val draining = async { control.drain() }
                delay(30)
                assertFalse(draining.isCompleted)
                release.complete(Unit)
                assertEquals("accepted add 128", request.await())
                draining.await()
                assertEquals(listOf(listOf("info"), listOf("compression", "LZ4"), listOf("add", "128")), commands)
            } finally {
                release.complete(Unit)
                control.drain()
            }
        }
    }

    @Test
    fun drain_closes_an_unadmitted_partial_frame_without_waiting_for_input() = runBlocking<Unit> {
        withTimeout(10000) {
            var executed = false
            val control = BtrfsMountControl.open(this, token) { executed = true; "unexpected" }
            val partial = SocketChannel.openWithRemote(control.address)
            try {
                partial.write(ByteBuffer(4).putInt(100).flip())
                delay(30)
                withTimeout(1000) { control.drain() }
                assertFalse(executed)
            } finally {
                partial.close()
                control.drain()
            }
        }
    }

    @Test
    fun immediate_owner_cancellation_closes_the_listener_and_joins_both_stages() = runBlocking<Unit> {
        withTimeout(10000) {
            val owner = SupervisorJob(coroutineContext[kotlinx.coroutines.Job])
            val control = BtrfsMountControl.open(CoroutineScope(coroutineContext + owner), token) { "unexpected" }
            owner.cancel()
            control.drain()
            owner.join()
            assertFailsWith<Exception> { BtrfsMountControl.request(control.address, token, listOf("info")) }
        }
    }
}
