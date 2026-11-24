package narchy.spacegraph.motion

// Port of upstream src/motion/director.ts at 125b369 (P4.1). The director owns verb ordering, timing, easing,
// stagger and reduced motion. A host registers handlers that mutate emphasis or camera state only; canonical node
// positions are never an output.
// Deviation: MOTION_VERBS and MOTION_TARGETS are enums (`MotionVerb.entries`), not string unions.
// Deviation: ease is a String key into MOTION_EASINGS; an unknown key fails fast.

enum class MotionVerb { arrive, emphasize, trace, flyTo, sweep, follow, decay, compact }

enum class MotionTarget { emphasis, camera }

/** One step of a named motion. `from`/`to` are the channel values the host handler receives. */
data class MotionStep(
    val id: String,
    val verb: MotionVerb,
    val target: MotionTarget,
    val from: Double,
    val to: Double,
    val duration: Double? = null,
    val delay: Double? = null,
)

/** Host-supplied policy. No verb, timing, easing or preference is a library default choice. */
data class MotionDirectorOptions(
    val ease: String = "power2.out",
    val duration: Double? = null,
    val stagger: Double? = null,
    val reducedMotion: Boolean = false,
)

data class MotionFrame(
    val step: MotionStep,
    /** Eased progress in [0, 1] for standard curves; spring curves may overshoot. */
    val progress: Double,
    val value: Double,
    /** Absolute director time in seconds. */
    val time: Double,
)

internal class ScheduledStep(val step: MotionStep, val start: Double, val duration: Double)

class MotionPlayback internal constructor(
    val duration: Double,
    private val scheduled: List<ScheduledStep>,
    private val emit: (ScheduledStep, Double) -> MotionFrame,
    private val clock: () -> Double,
    private val advanceClock: (Double) -> Unit,
    reducedMotion: Boolean,
) {
    val frames: MutableList<MotionFrame> = mutableListOf()
    private var remaining: MutableList<ScheduledStep> = if (reducedMotion) mutableListOf() else scheduled.toMutableList()

    val done: Boolean get() = remaining.isEmpty()
    val active: List<String> get() = remaining.map { it.step.id }

    fun advance(seconds: Double): List<MotionFrame> {
        advanceClock(maxOf(0.0, seconds))
        val emitted = mutableListOf<MotionFrame>()
        val kept = mutableListOf<ScheduledStep>()
        for (entry in remaining) {
            if (clock() < entry.start) {
                kept += entry
                continue
            }
            val t = minOf(1.0, (clock() - entry.start) / maxOf(entry.duration, 1e-6))
            emitted += emitFrame(entry, t)
            if (t < 1.0) kept += entry
        }
        remaining = kept
        return emitted
    }

    fun finish(): List<MotionFrame> {
        val emitted = remaining.map { emitFrame(it, 1.0) }
        remaining = mutableListOf()
        return emitted
    }

    internal fun emitFrame(entry: ScheduledStep, t: Double): MotionFrame {
        val frame = emit(entry, t)
        frames += frame
        return frame
    }
}

/**
 * Play named verbs against a host clock. `onFrame` is called once per step per advance; reduced motion
 * emits only the final frame of every step, so visually identical state is reached without animation.
 */
fun playMotion(
    steps: List<MotionStep>,
    onFrame: (MotionFrame) -> Unit,
    options: MotionDirectorOptions = MotionDirectorOptions(),
): MotionPlayback {
    val ease = MOTION_EASINGS[options.ease] ?: error("unknown ease ${options.ease}")
    val scheduled = steps.mapIndexed { index, step ->
        ScheduledStep(
            step = step,
            start = step.delay ?: (index * (options.stagger ?: 0.0)),
            duration = step.duration ?: options.duration ?: 0.5,
        )
    }
    val duration = scheduled.fold(0.0) { end, entry -> maxOf(end, entry.start + maxOf(0.0, entry.duration)) }
    var time = 0.0

    val emit = { entry: ScheduledStep, t: Double ->
        val progress = if (entry.duration > 0) ease(t) else 1.0
        MotionFrame(
            step = entry.step,
            progress = progress,
            value = entry.step.from + (entry.step.to - entry.step.from) * progress,
            time = time,
        ).also(onFrame)
    }

    val playback = MotionPlayback(
        duration = duration,
        scheduled = scheduled,
        emit = emit,
        clock = { time },
        advanceClock = { time += it },
        reducedMotion = options.reducedMotion,
    )
    if (options.reducedMotion) for (entry in scheduled) playback.emitFrame(entry, 1.0)
    return playback
}

/** A host registers the visual behavior of each verb; unregistered verbs degrade to no visual output. */
class MotionDirector {
    private val handlers = LinkedHashMap<MotionVerb, (MotionFrame) -> Unit>()

    fun register(verb: MotionVerb, handler: (MotionFrame) -> Unit): MotionDirector {
        handlers[verb] = handler
        return this
    }

    fun verbs(): List<MotionVerb> = handlers.keys.toList()

    fun play(steps: List<MotionStep>, options: MotionDirectorOptions = MotionDirectorOptions()): MotionPlayback =
        playMotion(steps, { frame -> handlers[frame.step.verb]?.invoke(frame) }, options)
}
