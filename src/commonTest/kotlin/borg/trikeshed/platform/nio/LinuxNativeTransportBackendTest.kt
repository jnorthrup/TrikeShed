package one.xio.spi

import borg.trikeshed.ccek.coroutineService
import borg.trikeshed.common.IoPlatform
import borg.trikeshed.common.SeekHandleService
import borg.trikeshed.common.platformSeekHandle
import borg.trikeshed.context.IoCapability
import borg.trikeshed.context.IoPreference
import borg.trikeshed.net.spi.SteeringSource
import borg.trikeshed.net.spi.TransportBackendService
import borg.trikeshed.net.spi.TransportBackendKind
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

@OptIn(ExperimentalCoroutinesApi::class)
class LinuxNativeTransportBackendTest {
    @Test
    fun linuxBackendProbesCapabilitiesWithoutClaimingEmulation() {
        val backend = LinuxNativeTransportBackend()
        val capabilities = backend.capabilities()

        val nativeLinux = System.getProperty("os.name").equals("Linux", ignoreCase = true)
        assertEquals(nativeLinux, capabilities.nativeLinux)
        assertFalse(capabilities.ioUringAvailable)
        assertFalse(capabilities.xdpAvailable)
        assertEquals(TransportBackendKind.LINUX_NATIVE, capabilities.backendKind)
    }

    @Test
    fun linuxBackendClassifiesIngressForSteering() {
        val backend = LinuxNativeTransportBackend()
        val decision = backend.classifyIngress("GET / HTTP/1.1\r\n\r\n".encodeToByteArray())

        assertEquals(0, decision.queueId)
        assertEquals(0, decision.workerId)
        assertEquals(SteeringSource.SOFTWARE_FALLBACK, decision.source)
    }

    @Test
    fun ccekRemainsInternalCapabilityInjectionForTransport() = runTest {
        val backend = SelectorTransportBackend()
        val ctx = TransportBackendService(backend) +
            SeekHandleService(platformSeekHandle()) +
            IoPreference(IoCapability.NIO)

        withContext(ctx) {
            val installedBackend = coroutineService(TransportBackendService.Key)
            val seekHandle = coroutineService(SeekHandleService.Key)

            assertNotNull(installedBackend)
            assertEquals(TransportBackendKind.SELECTOR, installedBackend.backend.kind)
            assertNotNull(seekHandle)
            assertEquals(IoPlatform.JVM_FILE_CHANNEL, IoPlatform.default())
        }
    }
}
