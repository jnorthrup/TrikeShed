package borg.literbike.modelmux

/**
 * Shared utility functions for model handling.
 * Ported from literbike/src/modelmux/utils.rs.
 */

/**
 * Returns the maximum context window that ModelMux should advertise for
 * newly cached models.
 */
fun maxContextWindow(): Long {
    return System.getenv("MODELMUX_MAX_CONTEXT_WINDOW")
        ?.toLongOrNull()
        ?: 128_000L
}
