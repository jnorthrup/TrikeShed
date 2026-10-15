package borg.trikeshed.hermes

import borg.trikeshed.graal.subvm.PythonSocketProvider
import borg.trikeshed.userspace.nio.channels.spi.ChannelOperations
import borg.trikeshed.userspace.nio.channels.spi.JvmChannelOperations
import borg.trikeshed.vm.Teleported
import kotlinx.coroutines.*
import modelmux.Airgap
import org.junit.jupiter.api.parallel.ResourceLock
import org.junit.jupiter.api.parallel.Resources
import kotlin.test.*

@ResourceLock(Resources.SYSTEM_PROPERTIES)
class HermesAirgapTest {
    @Test fun ownerContextSurvivesSynchronousBridgeBeforeDnsAndTransport() = runBlocking {
        var resolutions = 0
        val operations = object : ChannelOperations by JvmChannelOperations() {
            override fun resolve(host: String): ByteArray? { resolutions++; error("DNS reached") }
        }
        PythonSocketProvider(CoroutineScope(coroutineContext + Airgap() + operations)).use { provider ->
            for (operation in listOf("open", "resolve", "connect", "tls", "read", "write")) {
                val reply = provider.call(listOf(Teleported.Str(operation), Teleported.Str("localhost")))
                assertFalse(reply.a)
                assertEquals(13, reply.b["errno"])
                assertEquals("airgap: remote model calls are disabled", reply.b["detail"])
            }
            assertTrue(provider.call(listOf(Teleported.Str("tls-policy"))).a)
        }
        assertEquals(0, resolutions)
    }

    @Test fun processSwitchRejectsExistingSocketUseButPermitsClose() = runBlocking {
        val previous = System.setProperty(Airgap.PROPERTY, "false")
        try {
            org.junit.jupiter.api.Assumptions.assumeFalse(Airgap.configured())
            PythonSocketProvider(CoroutineScope(coroutineContext + Airgap(false))).use { provider ->
                val opened = provider.call(listOf(Teleported.Str("open"), Teleported.Num(2)))
                assertTrue(opened.a)
                val id = Teleported.Num((opened.b["value"] as Number).toLong())
                System.setProperty(Airgap.PROPERTY, "true")
                val rejected = provider.call(listOf(Teleported.Str("write"), id, Teleported.Str("41"), Teleported.Num(1000)))
                assertFalse(rejected.a)
                assertEquals(13, rejected.b["errno"])
                assertTrue(provider.call(listOf(Teleported.Str("close"), id)).a)
            }
        } finally {
            if (previous == null) System.clearProperty(Airgap.PROPERTY) else System.setProperty(Airgap.PROPERTY, previous)
        }
    }
}
