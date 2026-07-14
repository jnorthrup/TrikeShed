package borg.trikeshed.job

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * E1 — Capability validation RED tests.
 *
 * Missing platform capabilities (file, browser transaction, Linux storage)
 * must fail before the first child opens.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class JobNexusCapabilityValidationTest {

    @Test
    fun missingFileCapabilityFailsBeforeOpen() = runTest {
        val spec = JobNexusSpec.builder()
            .storage { backend(StorageBackend.File); durability(Durability.Fsync) }
            .channels { commands(64); committed(64); facts(128); activations(64); telemetry(32) }
            .build()

        val bindings = JobNexusBindings(
            parentScope = this,
            fileOps = null, // no file capability
        )

        try {
            JobNexusFactory.open(spec, bindings)
            fail("must reject when file capability is missing")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("file") || e.message!!.contains("capability"))
        }
    }

    @Test
    fun missingLinuxStorageCapabilityFailsBeforeOpen() = runTest {
        val spec = JobNexusSpec.builder()
            .storage { backend(StorageBackend.LinuxBtrfs); durability(Durability.Fsync) }
            .channels { commands(64); committed(64); facts(128); activations(64); telemetry(32) }
            .build()

        val bindings = JobNexusBindings(
            parentScope = this,
            linuxStorageAvailable = false,
        )

        try {
            JobNexusFactory.open(spec, bindings)
            fail("must reject when Linux storage is not available")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("linux") || e.message!!.contains("btrfs") || e.message!!.contains("capability"))
        }
    }
}
