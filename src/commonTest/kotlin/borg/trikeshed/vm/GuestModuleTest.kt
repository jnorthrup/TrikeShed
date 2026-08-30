package borg.trikeshed.vm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The guest-module shape, tested where it lives. These run on every target on purpose: a module is
 * continuity, and the daemon that mounts one on the JVM is the last mile, not the definition.
 */
class GuestModuleTest {

    private val manifest = GuestModuleManifest(
        module = "camel",
        declared = listOf("org.apache.camel:camel-core:4.8.5", "org.apache.camel:camel-main:4.8.5"),
        entries = listOf(
            GuestModuleEntry("camel-api-4.8.5.jar", 612938, "edda39dd"),
            GuestModuleEntry("camel-base-4.8.5.jar", 198582, "5466e583"),
        ),
    )

    @Test
    fun classesMountBeforeJarsAndJarsAreOrdered() {
        // `classes/` first is the debugging seam — loose classes must shadow the jar, not the
        // other way round — and unsorted jars would mean shadowing that varies between mounts.
        assertEquals(
            listOf("classes", "lib/a.jar", "lib/b.jar", "lib/c.jar"),
            GuestModuleLayout.mountOrder(hasClasses = true, jars = listOf("c.jar", "a.jar", "b.jar")),
        )
        assertEquals(
            listOf("lib/a.jar", "lib/b.jar"),
            GuestModuleLayout.mountOrder(hasClasses = false, jars = listOf("b.jar", "a.jar")),
        )
    }

    @Test
    fun aManifestRoundTripsThroughItsOwnRendering() {
        val reparsed = GuestModuleManifest.parse(manifest.render())
        assertEquals(manifest.module, reparsed.module)
        assertEquals(manifest.declared, reparsed.declared, "declared coordinates are the audit trail")
        assertEquals(manifest.entries, reparsed.entries)
        assertEquals(811520L, reparsed.totalBytes)
    }

    @Test
    fun aTornRowCostsItsOwnRowAndNothingElse() {
        val damaged = manifest.render().lines().toMutableList().also { it.add(3, "garbage\tnot-a-number") }
        val parsed = GuestModuleManifest.parse(damaged.joinToString("\n"))
        assertEquals(manifest.entries, parsed.entries, "a bad line must not take healthy rows with it")
    }

    @Test
    fun anIntactMountVerifies() {
        val v = verifyGuestModule(manifest, manifest.entries)
        assertTrue(v.ok, "${v.problems}")
        assertEquals(2, v.checked)
    }

    @Test
    fun everyWayAMountCanDriftIsReported() {
        val missing = verifyGuestModule(manifest, listOf(manifest.entries[0]))
        assertFalse(missing.ok)
        assertTrue(missing.problems.any { "missing" in it && "camel-base" in it }, "${missing.problems}")

        val resized = verifyGuestModule(manifest, listOf(manifest.entries[0], manifest.entries[1].copy(size = 1)))
        assertTrue(resized.problems.any { "size drift" in it }, "${resized.problems}")

        val rehashed = verifyGuestModule(manifest, listOf(manifest.entries[0], manifest.entries[1].copy(sha256 = "ff")))
        assertTrue(rehashed.problems.any { "sha256 drift" in it }, "${rehashed.problems}")

        // The one a loop over manifest rows alone cannot see: a jar nothing accounts for is still
        // code the daemon will execute.
        val smuggled = verifyGuestModule(manifest, manifest.entries + GuestModuleEntry("evil.jar", 10, "aa"))
        assertFalse(smuggled.ok)
        assertTrue(smuggled.problems.any { "unmanifested" in it && "evil.jar" in it }, "${smuggled.problems}")
    }

    @Test
    fun anAbsentManifestIsUnverifiableRatherThanFine() {
        // Nothing to compare is not the same as nothing wrong; reporting it as success is exactly
        // how an unpinned classpath passes an audit.
        val v = verifyGuestModule(GuestModuleManifest("corenlp"), listOf(GuestModuleEntry("x.jar", 1, "ab")))
        assertFalse(v.ok)
        assertEquals(0, v.checked)
        assertTrue(v.problems.single().contains("unverifiable"), "${v.problems}")
    }
}
