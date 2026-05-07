package borg.trikeshed.tls

import kotlin.test.*
import kotlinx.coroutines.runBlocking

class TlsElementTest {
    @Test
    fun openClose() = runBlocking {
        val engine = TlsEngineJdk()
        val elem = TlsElement(engine)
        elem.open()
        assertTrue(true)
        elem.close()
    }
}
