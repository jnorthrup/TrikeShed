package borg.trikeshed.agent.modelmux

import borg.trikeshed.narsese.Jev
import kotlinx.coroutines.*
import kotlin.test.*

class AirgapProcessTest {
    @Test fun processSwitchSurvivesMissingOrFalseCoroutineElement() {
        val previous = System.getProperty(Airgap.PROPERTY)
        System.setProperty(Airgap.PROPERTY, "true")
        try {
            runBlocking {
                assertFailsWith<Airgap.Rejection> {
                    Jev.ask("configured-test-token", "must not leave this process", emptyMap())
                }
                withContext(Airgap(false)) {
                    assertTrue(Airgap.active())
                    assertFailsWith<Airgap.Rejection> { Airgap.requireRemote() }
                }
            }
        } finally {
            if (previous == null) System.clearProperty(Airgap.PROPERTY)
            else System.setProperty(Airgap.PROPERTY, previous)
        }
    }
}
