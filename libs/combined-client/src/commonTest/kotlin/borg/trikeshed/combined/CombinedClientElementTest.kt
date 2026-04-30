package borg.trikeshed.combined

import borg.trikeshed.context.ElementState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class CombinedClientElementTest {

    @Test
    fun testCombinedClientLifecycle() = runTest {
        val client = CombinedClientElement()
        assertEquals(ElementState.CREATED, client.lifecycleState)

        client.open()
        assertEquals(ElementState.OPEN, client.lifecycleState)
        assertEquals(ElementState.OPEN, client.quic.lifecycleState)
        assertEquals(ElementState.OPEN, client.sctp.lifecycleState)
        assertEquals(ElementState.OPEN, client.htx.lifecycleState)

        val response = client.executeRpc("ipfs", listOf("ipfs://bafybeigdyrzt5sfp7udm7hu76uh7y26nf3efuylqabf3oclgtqy55fbzdi"))
        assertTrue(response.contains("HTX/IPFS target executed"))

        client.close()
        assertEquals(ElementState.CLOSED, client.lifecycleState)
        assertEquals(ElementState.CLOSED, client.quic.lifecycleState)
        assertEquals(ElementState.CLOSED, client.sctp.lifecycleState)
        assertEquals(ElementState.CLOSED, client.htx.lifecycleState)
    }
}
