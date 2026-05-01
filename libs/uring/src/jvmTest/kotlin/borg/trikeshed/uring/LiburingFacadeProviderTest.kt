package borg.trikeshed.uring

import borg.trikeshed.context.ElementState
import borg.trikeshed.context.LiburingFacadeSpi
import borg.trikeshed.context.*
import kotlinx.coroutines.test.runTest
import java.util.ServiceLoader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertIs

class LiburingFacadeProviderTest {
    @Test
    fun serviceLoaderDiscoversProvider() {
        val provider = ServiceLoader.load(LiburingFacadeSpi::class.java).firstOrNull()
        assertIs<LiburingFacadeProvider>(provider)
        assertIs<LiburingFacadeProvider>(loadLiburingFacadeSpi())
    }

    @Test
    fun liburingFacadeElementTransitionsToOpenThenClosed() = runTest {
        val element = LiburingFacadeElement()

        assertEquals(ElementState.CREATED, element.state)
        element.open()
        // Our new implementation transitions directly from CREATED to ACTIVE via OPEN
        assertEquals(ElementState.ACTIVE, element.state)
        element.close()
        assertEquals(ElementState.CLOSED, element.state)
    }

    @Test
    fun submitReturnsZero() {
        val provider = LiburingFacadeProvider()

        assertEquals(0, provider.submit().getOrThrow())
    }
}
