package modelmux

import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class AirgapTest {
    @Test fun eitherSwitchOverridesAnExplicitFalse() {
        assertFalse(Airgap.configured(null, null))
        for (off in listOf("false", "0", "no", "off", " FALSE ")) {
            assertFalse(Airgap.configured(off, off))
            assertTrue(Airgap.configured("true", off))
            assertTrue(Airgap.configured(off, "1"))
        }
    }

    @Test fun malformedOrEmptySwitchCannotEnableRemoteCalls() {
        for (on in listOf("true", "1", "yes", "on", " TRUE ", "", "flase")) {
            assertTrue(Airgap.configured(on, null))
            assertTrue(Airgap.configured(null, on))
        }
    }

    @Test fun childWorkInheritsAdmissionRestriction() = runTest {
        withContext(Airgap()) {
            assertTrue(Airgap.active())
            async {
                assertFailsWith<Airgap.Rejection> { Airgap.requireRemote() }
            }.await()
        }
    }
}
