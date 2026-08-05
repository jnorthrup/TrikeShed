package borg.trikeshed.collections

/**
 * Zoom aperture bands for the RTS view.
 *
 * Maps continuous zoom levels into discrete bands to control LOD (Level of Detail)
 * in the HUD and gallery.
 *
 * - [L0]: Macro view (furthest out, whole board visible).
 * - [L1]: Region view (clusters of nodes/content).
 * - [L2]: Focused view (reading distance for nodes).
 * - [L3]: Micro view (closest zoom, inspecting details).
 */
enum class LineAperture {
    L0, L1, L2, L3
}
