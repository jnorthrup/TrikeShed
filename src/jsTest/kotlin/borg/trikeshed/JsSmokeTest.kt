package borg.trikeshed

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Node-only smoke test for the shipped JS bundle.
 *
 * The production executable is published as a UMD library named `TrikeShed`,
 * so the headless JS runner can prove the bundle parses/loads by checking the
 * exported global without requiring a browser or Karma/electron.
 */
class JsSmokeTest {

    @Test
    fun bundleExposesTrikeShedGlobal() {
        val hasGlobal = js("typeof globalThis.TrikeShed !== 'undefined'") as Boolean
        assertTrue(hasGlobal, "expected the production JS bundle to expose globalThis.TrikeShed")
        assertNotNull(js("globalThis.TrikeShed"), "expected globalThis.TrikeShed to be defined")
    }
}
