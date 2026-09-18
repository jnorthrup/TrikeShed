package borg.trikeshed.landscape

import borg.trikeshed.parse.json.JsonSupport
import kotlin.math.exp
import kotlin.math.hypot

/**
 * LandscapeNavigation — the view-semantics core of the spatial blackboard medium,
 * ported clean-room from `landscape-navigation.js` (last seen at feb5af4db).
 *
 * A bookmark is a view projection, never an execution command or a document edit.
 * Everything here is pure: the SVG attribute writes (`projectWires`/`projectCurve`)
 * stay in the Kotlin/JS page adapter; the geometry they wrote (`clipCurve`, the
 * path `d` text, the world-space clip box) is here and testable.
 */

data class Pt(val x: Double, val y: Double)
data class Extent(val width: Double, val height: Double)
data class Rect(val left: Double, val top: Double, val right: Double, val bottom: Double)

enum class CalloutSide { LEFT, RIGHT, ABOVE, BELOW }

data class CalloutPlacement(val at: Pt, val side: CalloutSide, val tail: Double)

/** camera over world space: translation (x, y) and zoom z */
data class LandscapeCamera(val x: Double, val y: Double, val z: Double)

data class ClipBox(val left: Double, val top: Double, val right: Double, val bottom: Double, val pad: Double)

/** world-space cubic bezier control polygon */
data class Cubic(val p0: Pt, val p1: Pt, val p2: Pt, val p3: Pt) {
    /** de Casteljau midpoint split into the left and right half-curves */
    fun split(): Pair<Cubic, Cubic> {
        val a = mid(p0, p1)
        val b = mid(p1, p2)
        val c = mid(p2, p3)
        val d = mid(a, b)
        val e = mid(b, c)
        val f = mid(d, e)
        return Cubic(p0, a, d, f) to Cubic(f, e, c, p3)
    }
}

fun mid(a: Pt, b: Pt): Pt = Pt((a.x + b.x) / 2, (a.y + b.y) / 2)

data class DecodedView(val camera: LandscapeCamera, val focus: String)

data object LandscapeNavigation {
    const val minZoom: Double = .01
    const val detailZoom: Double = 4.0
    const val absoluteZoom: Double = 4e9

    fun maxZoom(scale: Double = 1.0): Double =
        minOf(absoluteZoom, detailZoom / (if (scale.isFinite() && scale > 0) scale else 1.0))

    fun wheelFactor(delta: Double): Double =
        if (delta.isFinite()) exp(maxOf(-.35, minOf(.35, -delta * .0025))) else 1.0

    /** place a `size` callout near `pointer` inside `bounds`, avoiding the pointer and the `subject` */
    fun callout(pointer: Pt, size: Extent, bounds: Rect, subject: Rect? = null, preferred: Pt? = null): CalloutPlacement? {
        val gap = 28.0
        fun clamp(v: Double, lo: Double, hi: Double): Double = maxOf(lo, minOf(hi, v))
        fun fit(p: Pt): Pt = Pt(
            clamp(p.x, bounds.left, bounds.right - size.width),
            clamp(p.y, bounds.top, bounds.bottom - size.height),
        )
        fun area(p: Pt, r: Rect): Double =
            maxOf(0.0, minOf(p.x + size.width, r.right) - maxOf(p.x, r.left)) *
                    maxOf(0.0, minOf(p.y + size.height, r.bottom) - maxOf(p.y, r.top))

        val origin = preferred ?: Pt(pointer.x + gap, pointer.y + 18.0)
        val candidates = mutableListOf(
            origin,
            Pt(pointer.x + gap, pointer.y - size.height / 2),
            Pt(pointer.x - size.width - gap, pointer.y - size.height / 2),
            Pt(pointer.x - size.width / 2, pointer.y + gap),
            Pt(pointer.x - size.width / 2, pointer.y - size.height - gap),
        )
        if (subject != null) candidates += listOf(
            Pt(subject.right + 16, origin.y),
            Pt(subject.left - size.width - 16, origin.y),
            Pt(origin.x, subject.bottom + 16),
            Pt(origin.x, subject.top - size.height - 16),
        )
        val exclusion = Rect(pointer.x - gap, pointer.y - gap, pointer.x + gap, pointer.y + gap)
        var best: Pt? = null
        var score: Triple<Int, Double, Double>? = null
        for (candidate in candidates) {
            val p = fit(candidate)
            val covered = if (subject != null) area(p, subject) else 0.0
            val rank = Triple(if (area(p, exclusion) > 0) 1 else 0, covered, hypot(p.x - origin.x, p.y - origin.y))
            val s = score
            if (best == null || rank.first < s!!.first ||
                (rank.first == s.first && (rank.second < s.second ||
                        (rank.second == s.second && rank.third < s.third)))
            ) {
                best = p
                score = rank
            }
        }
        val b = best!!
        if (score!!.first != 0 || size.width > bounds.right - bounds.left || size.height > bounds.bottom - bounds.top) return null
        val side = when {
            pointer.x < b.x -> CalloutSide.RIGHT
            pointer.x > b.x + size.width -> CalloutSide.LEFT
            pointer.y < b.y -> CalloutSide.BELOW
            else -> CalloutSide.ABOVE
        }
        val horizontal = side == CalloutSide.LEFT || side == CalloutSide.RIGHT
        val tail = clamp(
            if (horizontal) pointer.y - b.y else pointer.x - b.x,
            12.0, (if (horizontal) size.height else size.width) - 12.0,
        )
        return CalloutPlacement(b, side, tail)
    }

    /** zoom toward `anchor`, clamped to [minZoom], `ceiling`, and [absoluteZoom]; a NaN request keeps the camera's z */
    fun zoomAt(camera: LandscapeCamera, requested: Double, anchor: Pt, ceiling: Double = detailZoom): LandscapeCamera {
        val z = maxOf(minZoom, minOf(ceiling, absoluteZoom, if (requested.isNaN()) camera.z else requested))
        val ratio = z / camera.z
        return LandscapeCamera(
            anchor.x - (anchor.x - camera.x) * ratio,
            anchor.y - (anchor.y - camera.y) * ratio,
            z,
        )
    }

    /**
     * Exact de Casteljau subdivision: an SVG clip alone still lets the stroker
     * tessellate millions of off-screen dashes at fractal zoom. Non-finite control
     * points yield no pieces, mirroring the source's guard.
     */
    fun clipCurve(curve: Cubic, box: ClipBox): List<Cubic> {
        val pieces = mutableListOf<Cubic>()
        var budget = 256
        fun visit(p: Cubic, depth: Int) {
            if (--budget < 0) return
            val left = minOf(p.p0.x, p.p1.x, p.p2.x, p.p3.x)
            val right = maxOf(p.p0.x, p.p1.x, p.p2.x, p.p3.x)
            val top = minOf(p.p0.y, p.p1.y, p.p2.y, p.p3.y)
            val bottom = maxOf(p.p0.y, p.p1.y, p.p2.y, p.p3.y)
            if (right < box.left || left > box.right || bottom < box.top || top > box.bottom) return
            if (left >= box.left - box.pad && right <= box.right + box.pad &&
                top >= box.top - box.pad && bottom <= box.bottom + box.pad
            ) {
                pieces += p
                return
            }
            if (depth == 32) return
            val (l, r) = p.split()
            visit(l, depth + 1)
            visit(r, depth + 1)
        }
        if (listOf(curve.p0, curve.p1, curve.p2, curve.p3).all { it.x.isFinite() && it.y.isFinite() }) visit(curve, 0)
        return pieces
    }

    /** the world-space clip box a viewport shows under a camera, padded by 32 screen pixels */
    fun clipBox(viewportWidth: Double, viewportHeight: Double, camera: LandscapeCamera): ClipBox {
        val pad = 32.0 / camera.z
        return ClipBox(
            left = (-camera.x - 32.0) / camera.z,
            top = (-camera.y - 32.0) / camera.z,
            right = (viewportWidth - camera.x + 32.0) / camera.z,
            bottom = (viewportHeight - camera.y + 32.0) / camera.z,
            pad = pad,
        )
    }

    /** path `d` text for the clipped pieces: a move only where the pen must jump */
    fun pathD(pieces: List<Cubic>): String {
        val sb = StringBuilder()
        var last: Pt? = null
        for (p in pieces) {
            if (last == null || last.x != p.p0.x || last.y != p.p0.y)
                sb.append("M ").append(p.p0.x).append(' ').append(p.p0.y).append(' ')
            sb.append("C ").append(p.p1.x).append(' ').append(p.p1.y).append(", ")
                .append(p.p2.x).append(' ').append(p.p2.y).append(", ")
                .append(p.p3.x).append(' ').append(p.p3.y).append(' ')
            last = p.p3
        }
        return sb.toString()
    }

    fun encode(camera: LandscapeCamera, focus: String = ""): String {
        val pairs = mutableListOf("x" to camera.x.toString(), "y" to camera.y.toString(), "z" to camera.z.toString())
        if (focus.isNotEmpty()) pairs += "focus" to focus
        return "#" + formEncode(pairs)
    }

    /** decode a bookmark hash; null unless x, y, z are all present, finite, and z is within [minZoom, absoluteZoom] */
    fun decode(hash: String): DecodedView? {
        val p = formDecode(hash.removePrefix("#"))
        if (!listOf("x", "y", "z").all(p::containsKey)) return null
        val camera = LandscapeCamera(
            p.getValue("x").toDoubleOrNull() ?: return null,
            p.getValue("y").toDoubleOrNull() ?: return null,
            p.getValue("z").toDoubleOrNull() ?: return null,
        )
        if (!listOf(camera.x, camera.y, camera.z).all(Double::isFinite)) return null
        if (camera.z < minZoom || camera.z > absoluteZoom) return null
        return DecodedView(camera, p["focus"] ?: "")
    }

    fun program(name: String): String = "program:" + name

    fun node(program: String, id: String): String = "node:" + JsonSupport.stringify(listOf(program, id))

    fun `object`(id: String): String = "object:" + id

    // application/x-www-form-urlencoded, matching URLSearchParams toString/parse so old bookmarks survive
    fun formEncode(pairs: List<Pair<String, String>>): String =
        pairs.joinToString("&") { (k, v) -> "${formEscape(k)}=${formEscape(v)}" }

    fun formDecode(text: String): Map<String, String> =
        if (text.isEmpty()) emptyMap() else text.split("&").mapNotNull {
            val i = it.indexOf('=')
            if (i < 0) null else formUnescape(it.substring(0, i)) to formUnescape(it.substring(i + 1))
        }.toMap()

    fun formEscape(s: String): String {
        val out = StringBuilder()
        for (b in s.encodeToByteArray()) {
            val c = b.toInt()
            if (c in 'A'.code..'Z'.code || c in 'a'.code..'z'.code || c in '0'.code..'9'.code ||
                c == '_'.code || c == '.'.code || c == '~'.code || c == '-'.code
            ) out.append(c.toChar())
            else if (c == ' '.code) out.append('+')
            else out.append('%').append((c shr 4).hexDigit()).append((c and 15).hexDigit())
        }
        return out.toString()
    }

    fun formUnescape(s: String): String {
        val bytes = mutableListOf<Byte>()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '+' -> { bytes += ' '.code.toByte(); i++ }
                c == '%' && i + 2 < s.length && s[i + 1].isHexDigit() && s[i + 2].isHexDigit() -> {
                    bytes += ((s[i + 1].digitToInt(16) shl 4) or s[i + 2].digitToInt(16)).toByte()
                    i += 3
                }
                else -> { bytes += c.code.toByte(); i++ }
            }
        }
        return bytes.toByteArray().decodeToString()
    }

    fun Int.hexDigit(): Char = "0123456789ABCDEF"[this]

    fun Char.isHexDigit(): Boolean = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'
}
