package borg.trikeshed.htx.client

import borg.trikeshed.context.AsyncContextElement
import kotlin.test.Test
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.CoroutineContext

class HtxElementTest {

    // 4a — context key
    @Test
    fun `HtxElementCompat implements AsyncContextElement with Key`() = runTest {
        val elem = HtxElementCompat()
        assertTrue(elem is AsyncContextElement)
        assertSame(HtxKey, elem.key)
    }

    // 4b — context lookup
    @Test
    fun `context lookup resolves via HtxKey`() = runTest {
        val elem = openHtxElement()
        val ctx: CoroutineContext = elem
        assertSame(elem, ctx[HtxKey])
    }
}
