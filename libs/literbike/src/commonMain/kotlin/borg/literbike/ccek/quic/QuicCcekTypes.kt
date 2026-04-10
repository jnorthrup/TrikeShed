package borg.literbike.ccek.quic

// ============================================================================
// CCEK Types -- ported from quic_ccek_types.rs
// ============================================================================

/** CCEK cadence parameters for timing control */
data class CcekCadence(
    val burstMs: UInt = 0u,
    val idleMs: UInt = 0u,
    val jitterMs: UInt = 0u
)

/** CCEK policy configuration */
data class CcekPolicy(
    val enableCover: Boolean = false,
    val cadence: CcekCadence = CcekCadence()
)
