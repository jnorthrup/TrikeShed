package borg.trikeshed.job

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JobNexusSpecValidatorTest {

    @Test
    fun rejectsNonPositiveActivationCapacity() {
        val spec = JobNexusSpec(
            channels = ChannelSpec(activations = 0),
        )

        val result = JobNexusSpecValidator.validate(spec)

        assertFalse(result.valid, "activation channel capacity must be positive")
        assertTrue(
            result.errors.any { it.contains("channels.activations") },
            "validation error must identify channels.activations: ${result.errors}",
        )
    }

    @Test
    fun rejectsNonPositiveTelemetryCapacity() {
        val spec = JobNexusSpec(
            channels = ChannelSpec(telemetry = 0),
        )

        val result = JobNexusSpecValidator.validate(spec)

        assertFalse(result.valid, "telemetry channel capacity must be positive")
        assertTrue(
            result.errors.any { it.contains("channels.telemetry") },
            "validation error must identify channels.telemetry: ${result.errors}",
        )
    }

    @Test
    fun rejectsNonPositiveDrainTimeout() {
        val spec = JobNexusSpec(
            supervision = SupervisionSpec(drainTimeoutMs = 0),
        )

        val result = JobNexusSpecValidator.validate(spec)

        assertFalse(result.valid, "drain timeout must be positive")
        assertTrue(
            result.errors.any { it.contains("supervision.drainTimeoutMs") },
            "validation error must identify supervision.drainTimeoutMs: ${result.errors}",
        )
    }
}
