package borg.trikeshed.forge.server

import borg.trikeshed.agent.keymux.HarnessProvider
import borg.trikeshed.lib.*
import kotlin.test.*

class DiscoveryEndpointTest {
    @Test fun keylessRosterEndpointOverridesRegistryDefault() {
        val provider = HarnessProvider("local", emptySeriesOf(), "https://default.invalid/v1")
        val selected = assertNotNull(discoveryEndpoint(provider, null, "http://127.0.0.1:18080/v1/"))
        assertEquals("http://127.0.0.1:18080/v1/", selected.a)
        assertTrue(selected.b)
        val configured = assertNotNull(discoveryEndpoint(provider, "https://other.invalid/v1", "http://127.0.0.1:18080/v1"))
        assertFalse(configured.b)
    }

    @Test fun publicListingRequiresNoKeyWithoutChangingItsEndpoint() {
        val provider = HarnessProvider("loom", emptySeriesOf(), "https://loom.invalid/v1", publicModels = true)
        val selected = assertNotNull(discoveryEndpoint(provider, null, null))
        assertEquals("https://loom.invalid/v1", selected.a)
        assertTrue(selected.b)
    }
}
