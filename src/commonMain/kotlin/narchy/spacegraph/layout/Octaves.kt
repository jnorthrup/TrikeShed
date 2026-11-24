package narchy.spacegraph.layout

// Port of upstream src/layout/octaves.ts at 125b369 (F01). Places a containment hierarchy at halving world scales.
// Deviation: Position is the port's Vec3 (Placement.kt typealias); the TypeScript `Position` import is that alias.
// Deviation: options are a data class with the upstream defaults; `ids` and `parentOf` are the same arguments.
// Deviation: `shape` is an enum, not a 'ring' | 'spiral' string union.

import narchy.spacegraph.Vec3
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

data class OctaveScale(val octave: Int, val scale: Double)

enum class OctaveShape { ring, spiral }

data class OctaveLayoutOptions(
    val card: Pair<Double, Double>,
    /** Deepest descendant level; level 0 keeps the full card size. */
    val maxDepth: Int = 2,
    /** Child-ring radius as a multiple of the parent card width. */
    val radiusFactor: Double = 0.85,
    /** Root-ring radius as a multiple of the parent card width. */
    val rootRadiusFactor: Double = 0.6,
    /** Siblings share one ring, or follow a golden-angle spiral. */
    val shape: OctaveShape = OctaveShape.ring,
    val phase: Double = 0.0,
)

data class OctaveLayout(
    val positions: Map<String, Position>,
    val octaves: Map<String, OctaveScale>,
    val bounds: Pair<Position, Position>,
)

/**
 * Place a containment hierarchy at halving world scales: a root at 1x, its children at 1/2x,
 * grandchildren at 1/4x. The same ring rule at every level makes the structure self-similar, so
 * one frame shows the whole range and zooming in promotes each smaller ring to a readable card.
 * The caller owns the parent map and all domain vocabulary.
 */
fun octaveLayout(
    ids: List<String>,
    parentOf: Map<String, String>,
    options: OctaveLayoutOptions,
): OctaveLayout {
    val known = ids.toSet()
    val children = LinkedHashMap<String, MutableList<String>>()
    val roots = mutableListOf<String>()
    for (id in ids) {
        val parent = parentOf[id]
        if (parent != null && parent in known && parent != id) {
            children.getOrPut(parent) { mutableListOf() }.add(id)
        } else roots += id
    }

    val positions = LinkedHashMap<String, Position>()
    val octaves = LinkedHashMap<String, OctaveScale>()
    val visited = HashSet<String>()
    val maxDepth = options.maxDepth
    val radiusFactor = options.radiusFactor
    val rootRadiusFactor = options.rootRadiusFactor
    val shape = options.shape
    val phaseOffset = options.phase
    val spread = { index: Int, count: Int ->
        if (shape == OctaveShape.spiral) index * 2.399963 else (2 * PI * index) / count
    }
    val reach = { base: Double, index: Int, count: Int ->
        if (shape == OctaveShape.spiral) base * (0.4 + (0.6 * index) / maxOf(1, count - 1)) else base
    }

    fun place(id: String, octave: Int, x: Double, y: Double) {
        if (!visited.add(id)) return
        val scale = 2.0.pow(octave)
        positions[id] = Vec3(x, y, 0.0)
        octaves[id] = OctaveScale(octave, scale)
        if (octave <= -maxDepth) return
        val siblings = children[id].orEmpty()
        if (siblings.isEmpty()) return
        val radius = options.card.first * scale * radiusFactor
        val phase = options.phase + octave * 0.37
        siblings.forEachIndexed { index, child ->
            val angle = spread(index, siblings.size) + (if (shape == OctaveShape.spiral) phaseOffset else phase)
            val distance = reach(radius, index, siblings.size)
            place(child, octave - 1, x + cos(angle) * distance, y + sin(angle) * distance)
        }
    }

    val rootRadius = options.card.first * rootRadiusFactor
    roots.forEachIndexed { index, id ->
        val angle = spread(index, roots.size) + (if (shape == OctaveShape.spiral) phaseOffset else 0.0)
        val distance = reach(rootRadius, index, roots.size)
        place(id, 0, cos(angle) * distance, sin(angle) * distance)
    }
    for (id in ids) if (id !in visited) place(id, 0, 0.0, 0.0)

    val xs = positions.values.map { it.x }
    val ys = positions.values.map { it.y }
    return OctaveLayout(
        positions = positions,
        octaves = octaves,
        bounds = Vec3(xs.min(), ys.min(), 0.0) to Vec3(xs.max(), ys.max(), 0.0),
    )
}
