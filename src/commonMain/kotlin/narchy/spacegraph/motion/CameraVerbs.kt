package narchy.spacegraph.motion

// Port of upstream src/motion/camera.ts at 125b369 (P4.2).
// Camera verbs are host-registered behaviors. The library sequences commands against a host adapter built from
// CameraController / CameraControls; it never chooses a flight or a zoom level.
// Deviation: TypeScript `readonly [number, number, number]` tuples are DoubleArray-free `Triple<Double, Double, Double>`.
// Deviation: `Record<CameraVerb, CameraHandler>` is a `when` over the enum (DEFAULT_HANDLER).

enum class CameraVerb { fit, focus, flyTo, orbit, zoom, reset }

val CAMERA_VERBS: List<CameraVerb> = CameraVerb.entries

data class CameraCommand(
    val verb: CameraVerb,
    val ids: List<String>? = null,
    val position: Triple<Double, Double, Double>? = null,
    val target: Triple<Double, Double, Double>? = null,
    val amount: Double? = null,
    val duration: Double? = null,
)

/** The host binds these to `fitView`, `focusNode`, `flyTo`, `orbit`, `zoomTo` and `resetCamera`. */
interface CameraAdapter {
    fun fit(ids: List<String>?, duration: Double)
    fun focus(id: String, duration: Double)
    fun flyTo(position: Triple<Double, Double, Double>, target: Triple<Double, Double, Double>, duration: Double)
    fun orbit(amount: Double, duration: Double)
    fun zoom(amount: Double, duration: Double)
    fun reset()
}

fun interface CameraHandler {
    fun handle(command: CameraCommand, adapter: CameraAdapter)
}

/** Content-relative bounds and opt-in orthographic orbit (gap 7), expressed as host policy. */
data class CameraPolicy(
    val orbitInOrthographic: Boolean,
    val contentRelativeZoom: Boolean,
    val zoomOutFactor: Double,
    val zoomInFactor: Double,
)

val DEFAULT_CAMERA_POLICY = CameraPolicy(
    orbitInOrthographic = false,
    contentRelativeZoom = true,
    zoomOutFactor = 1.0 / 3.0,
    zoomInFactor = 2.5,
)

data class ZoomBounds(val min: Double, val max: Double)

fun orbitEnabled(orthographic: Boolean, policy: CameraPolicy = DEFAULT_CAMERA_POLICY): Boolean =
    !orthographic || policy.orbitInOrthographic

fun contentZoomBounds(fitZoom: Double, policy: CameraPolicy = DEFAULT_CAMERA_POLICY): ZoomBounds {
    if (!policy.contentRelativeZoom) return ZoomBounds(0.01, 100.0)
    return ZoomBounds(fitZoom * policy.zoomOutFactor, fitZoom * policy.zoomInFactor)
}

private fun defaultHandler(verb: CameraVerb): CameraHandler = when (verb) {
    CameraVerb.fit -> CameraHandler { c, a -> a.fit(c.ids, c.duration ?: 1.5) }
    CameraVerb.focus -> CameraHandler { c, a -> a.focus(c.ids?.firstOrNull() ?: "", c.duration ?: 1.0) }
    CameraVerb.flyTo -> CameraHandler { c, a ->
        val p = c.position
        val t = c.target
        if (p != null && t != null) a.flyTo(p, t, c.duration ?: 1.5)
    }
    CameraVerb.orbit -> CameraHandler { c, a -> a.orbit(c.amount ?: 0.0, c.duration ?: 1.5) }
    CameraVerb.zoom -> CameraHandler { c, a -> a.zoom(c.amount ?: 1.0, c.duration ?: 1.0) }
    CameraVerb.reset -> CameraHandler { _, a -> a.reset() }
}

/** Registered verbs override the neutral defaults; an unknown verb is a no-op. */
class CameraVerbRegistry {
    private val handlers = LinkedHashMap<CameraVerb, CameraHandler>()

    fun register(verb: CameraVerb, handler: CameraHandler): CameraVerbRegistry {
        handlers[verb] = handler
        return this
    }

    fun verbs(): List<CameraVerb> = handlers.keys.toList()

    fun run(command: CameraCommand, adapter: CameraAdapter) {
        val handler = handlers[command.verb] ?: defaultHandler(command.verb)
        handler.handle(command, adapter)
    }
}
