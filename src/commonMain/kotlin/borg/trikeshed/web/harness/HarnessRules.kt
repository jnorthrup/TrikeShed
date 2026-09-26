package borg.trikeshed.web.harness

import borg.trikeshed.landscape.LandscapeCamera
import borg.trikeshed.landscape.LandscapeNavigation
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Which shell the harness serves, from the location pathname. */
enum class HarnessSurface(val key: String, val title: String) {
    Panels("panels", "Panels"), Graal("graal", "Graal"), Board("board", "Blackboard");

    companion object {
        private val PANELS = Regex("^/panels(?:\\.html)?/?$")
        private val GRAAL = Regex("^/graal/?$")
        fun of(pathname: String): HarnessSurface = when {
            PANELS.matches(pathname) -> Panels
            GRAAL.matches(pathname) -> Graal
            else -> Board
        }
    }

    /** The pathname a selected program is bookmarked under. */
    val pathname: String get() = if (this == Board) "/harness" else "/$key"
}

object HarnessKeys {
    const val PROGRAM = "lcnc/program/"
    const val RUN = "lcnc/run/"
    const val PUBLISH = "lcnc/publish/"
    const val SNAPSHOT = "lcnc/snapshot/"
    const val STALE = "lcnc/stale/"
    const val VOCABULARY = "lcnc/vocabulary"
    const val SNAPSHOT_HEAD = "lcnc/snapshot/head"

    fun prefix(key: String): String = key.split("/")[0]
    fun programName(key: String): String = key.substring(PROGRAM.length)
    fun isActiveRunStatus(status: Any?): Boolean = status == "validating" || status == "running"

    private val PROGRAM_NAME = Regex("^[a-z0-9][a-z0-9._-]*$")
    fun validProgramName(name: String): Boolean = PROGRAM_NAME.matches(name)

    private val LOCAL_SEQ = Regex("^n\\d+$")
    /** The document `seq` a local node id demands: `n7` needs 8. */
    fun seqFloor(localId: String): Double = if (LOCAL_SEQ.matches(localId)) localId.substring(1).toDouble() + 1 else 1.0
}

/** Territory geometry and tones of the board render. */
object HarnessTerritory {
    const val PROGRAM_TONE = "#64ceca"
    const val RECEIPT_TONE = "#91c49d"
    const val ACTOR_TONE = "#d59db1"
    const val VOCABULARY_TONE = "#80b4d2"
    const val OBJECT_TONE = "#84a7bf"

    fun tone(prefix: String): String = when (prefix) {
        "narsese" -> "#c4b07c"
        "kanban" -> "#80b4d2"
        else -> "#a7a1c9"
    }

    fun factHeight(count: Int): Double = max(190.0, min(560.0, 140 + sqrt(count.toDouble()) * 11))

    const val NARSESE_LIMIT = 36
    fun narseseHeight(visible: Int, rest: Int): Double =
        96.0 + visible * 34 + (if (rest > 0) 60 + ceil(rest / 50.0) * 12 else 0.0)

    const val FACT_FIELD_THRESHOLD = 24
}

/** Narsese on the board is shown as expressions and linked by their terms. */
object NarseseRows {
    data class Terms(val head: String, val tail: String)

    fun expression(expression: String?, antecedent: String?, consequent: String?, subject: String?, obj: String?): String? = when {
        !expression.isNullOrEmpty() -> expression
        !antecedent.isNullOrEmpty() && !consequent.isNullOrEmpty() -> "$antecedent ==> $consequent"
        !subject.isNullOrEmpty() && !obj.isNullOrEmpty() -> "$subject --> $obj"
        else -> null
    }

    // "source: head ==> tail (note)" — the source prefix is provenance, not a term
    private val STATEMENT = Regex("^(?:[^:«»]+:\\s)?(.+?)\\s(==>|-->|<->|=/>|--/>|→)\\s(.+?)(\\s\\(.*\\))?$")

    fun terms(expression: String?, antecedent: String?, consequent: String?, subject: String?, obj: String?): Terms? {
        val head = antecedent.orEmpty().ifEmpty { subject.orEmpty() }
        val tail = consequent.orEmpty().ifEmpty { obj.orEmpty() }
        if (head.isNotEmpty() && tail.isNotEmpty()) return Terms(head, tail)
        val m = STATEMENT.find(expression.orEmpty()) ?: return null
        return Terms(m.groupValues[1], m.groupValues[3])
    }

    fun rank(event: Any?): Int = when (event) {
        "minted" -> 0
        "dependent-rete-firing" -> 1
        "revised" -> 2
        else -> 3
    }
}

/** Invocation bindings (harness-arguments.js). */
object InvocationLimits {
    const val BINDINGS = 64
    const val NAME = 128
    const val BYTES = 65536
    const val RESOLVED_ROWS = 1024

    fun checkCount(count: Int) { if (count > BINDINGS) throw IllegalArgumentException("At most 64 invocation bindings") }
    fun checkName(key: String) { if (key.isEmpty() || key.length > NAME) throw IllegalArgumentException("Invalid argument name") }
    fun checkSize(total: Int) { if (total > BYTES) throw IllegalArgumentException("Invocation bindings exceed 64 KiB") }

    /** An `add` refuses empty, overlong or duplicate names. */
    fun acceptsNew(name: String, present: Boolean): Boolean = name.isNotEmpty() && name.length <= NAME && !present

    /** `scope.in` names are bound without their optional `?` suffix. */
    fun bindingName(name: String): String = name.removeSuffix("?")
}

/** Which run receipt the argument inspector keeps per program (HarnessArguments.record). */
object ReceiptOrder {
    class Stamp(val runId: Any?, val timelineRevision: Double, val startedAtMs: Any?, val startedAt: Double, val sequence: Double)

    /** True when [old] supersedes [next]. Mirrors JS `Number(undefined)` → NaN comparisons. */
    fun keepsOld(old: Stamp, next: Stamp): Boolean {
        if (old.runId == next.runId && old.timelineRevision > next.timelineRevision) return true
        if (old.runId != next.runId && old.startedAt > next.startedAt) return true
        if (old.startedAtMs == next.startedAtMs && old.sequence > next.sequence) return true
        return false
    }
}

/** Board flashes: each key keeps its own expiry so a burst fades on independent clocks. */
object FlashClock {
    const val HOLD_MS = 1000.0
    const val SWEEP_MS = 250
}

/** Snap to the innermost program territory under the pointer once it fills the view. */
object ZoomSnap {
    class Territory(val key: String, val x: Double, val y: Double, val w: Double, val h: Double)

    fun innermost(territories: List<Territory>, camera: LandscapeCamera, px: Double, py: Double): Territory? {
        var inner: Territory? = null
        var innerSize = Double.POSITIVE_INFINITY
        for (a in territories) {
            if (!a.key.startsWith(HarnessKeys.PROGRAM)) continue
            val x = a.x * camera.z + camera.x; val y = a.y * camera.z + camera.y
            val w = a.w * camera.z; val h = a.h * camera.z
            if (px < x || py < y || px > x + w || py > y + h) continue
            if (w * h < innerSize) { innerSize = w * h; inner = Territory(a.key, x, y, w, h) }
        }
        return inner
    }

    /** Filling is measured per axis; a fraction of viewport area alone is aspect-blind. */
    fun arrived(box: Territory, width: Double, height: Double): Boolean {
        val visible = max(0.0, min(width, box.x + box.w) - max(0.0, box.x)) *
            max(0.0, min(height, box.y + box.h) - max(0.0, box.y))
        return !(box.w < width * .9 && box.h < height * .9 && visible < width * height * .55)
    }
}

/** Connection verdict ordering for the shake report: unresolved ports first. */
object ConnectionVerdicts {
    const val SHOWN = 100
    fun settled(status: Any?): Boolean = status == "optional" || status == "binding" || status == "ok"
    fun offersMate(dir: Any?, status: Any?): Boolean = dir == "in" && status != "ok" && status != "binding"
}

/** A world-space box: territory, object rect or element bounds. */
data class WorldBox(val x: Double, val y: Double, val w: Double, val h: Double)

/** The camera that fits a world box into the viewport with a 30px pad (Harness.focus). */
fun focusCamera(box: WorldBox, width: Double, height: Double, scale: Double): LandscapeCamera {
    val pad = 30.0
    val z = min(LandscapeNavigation.maxZoom(scale), max(LandscapeNavigation.minZoom, min((width - pad * 2) / box.w, (height - pad * 2) / box.h)))
    return LandscapeCamera((width - box.w * z) / 2 - box.x * z, (height - box.h * z) / 2 - box.y * z, z)
}
