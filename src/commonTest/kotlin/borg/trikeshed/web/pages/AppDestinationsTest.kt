package borg.trikeshed.web.pages

import kotlin.test.*

class AppDestinationsTest {
    @Test fun piAndHermesHaveIndependentRoutesAndVisibleNavigation() {
        val pi = assertNotNull(AppDestinations.current("/pi"))
        val hermes = assertNotNull(AppDestinations.current("/hermes"))
        assertEquals("pi", pi.id)
        assertEquals("hermes", hermes.id)
        assertTrue(AppDestinations.accepts(pi, "/pi/"))
        assertFalse(AppDestinations.accepts(pi, "/hermes"))
        assertFalse(AppDestinations.accepts(hermes, "/pi"))
        assertTrue(AppDestinations.primary.containsAll(listOf(pi, hermes)))
    }
}
