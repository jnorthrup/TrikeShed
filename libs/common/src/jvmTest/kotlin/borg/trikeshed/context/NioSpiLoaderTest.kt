package borg.trikeshed.context

import kotlin.test.Test
import kotlin.test.assertFailsWith

class NioSpiLoaderTest {
    @Test fun userspaceNioSpiThrowsWhenNoProvider() {
        assertFailsWith<IllegalStateException> { loadUserspaceNioSpi() }
    }
    @Test fun liburingFacadeSpiThrowsWhenNoProvider() {
        assertFailsWith<IllegalStateException> { loadLiburingFacadeSpi() }
    }
}
