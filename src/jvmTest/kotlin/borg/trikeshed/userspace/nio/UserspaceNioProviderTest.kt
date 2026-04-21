package borg.trikeshed.userspace.nio

import borg.trikeshed.context.ElementState
import borg.trikeshed.context.UserspaceNioSpi
import borg.trikeshed.userspace.reactor.Interest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import java.util.ServiceLoader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class UserspaceNioProviderTest {
    @Test
    fun providerLoadsAndRegisterPathDoesNotThrow() = runTest {
        val provider = ServiceLoader.load(UserspaceNioSpi::class.java).find { it is UserspaceNioProvider }
        assertNotNull(provider)

        val element = provider.open(41)
        assertTrue(element is UserspaceNioProvider.NioElement)
        assertEquals(ElementState.OPEN, element.state)
        provider.close(element)
        assertEquals(ElementState.CLOSED, element.state)

        val backend = NioSpiBackend(provider)
        val token = Reactor(backend).register(fd = 42, interest = Interest.READ)
        assertTrue(token > 0)
        backend.unregister(42).getOrThrow()
    }
}
