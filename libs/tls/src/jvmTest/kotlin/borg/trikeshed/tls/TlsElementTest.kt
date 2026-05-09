package borg.trikeshed.tls

import kotlin.test.*
import kotlinx.coroutines.runBlocking

class TlsElementTest {
    @Test
    fun openClose() = runBlocking {
        val elem = TlsElement()
        elem.open()
        assertTrue(true)
        elem.close()
    }
}
