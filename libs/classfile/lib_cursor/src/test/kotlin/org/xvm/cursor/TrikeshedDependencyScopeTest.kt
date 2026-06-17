package org.xvm.cursor

import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import borg.trikeshed.lib.ChunkedMutableSeries

/**
 * TDD RED: verify kotlin is linked and hosting a special xvm from lib_cursor
 * and that borg.trikeshed.confix.Confix and all other Trikeshed imports are in
 * the scope of org.xvm.cursor via publishMaven in or out.
 *
 * Confix is not yet on the classpath (TODO #1). This test uses Class.forName
 * so it compiles now but fails RED at runtime until the artifact is published.
 */
class TrikeshedDependencyScopeTest {

    @Test
    fun `Trikeshed Confix class is available on classpath via publishMavenLocal`() {
        // Verify that Trikeshed base types ARE visible (this should pass already)
        val chunked = ChunkedMutableSeries::class.java
        assertNotNull(chunked, "borg.trikeshed.lib must be on classpath")

        // Verify that borg.trikeshed.confix.ConfixOracleFacade is available on the classpath.
        val confixClass = Class.forName("borg.trikeshed.confix.ConfixOracleFacade")
        assertNotNull(confixClass, "borg.trikeshed.confix.ConfixOracleFacade must be on the classpath")
    }

    @Test
    fun `borg trikeshed parse confix package available via publishMavenLocal`() {
        // RED: parse.confix sub-package not in published TrikeShed-jvm artifact yet
        val saxEventClass = Class.forName("borg.trikeshed.parse.confix.SaxEvent")
        assertNotNull(saxEventClass, "borg.trikeshed.parse.confix.SaxEvent must be on classpath (TODO: publishMavenLocal)")
    }
}
