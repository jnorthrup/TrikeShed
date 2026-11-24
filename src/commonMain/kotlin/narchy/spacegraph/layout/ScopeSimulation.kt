package narchy.spacegraph.layout

// Port of upstream src/layout/scope-simulation.ts at 125b369.
// The physical layer over recursive scopes. A scope layout is a stable target; bodies are springs that chase it.
// Critical damping and sleep-on-settle make the motion readable and deterministic: animation is a transition
// between resting states, never a perpetual simulation.
// No renderer, clock or DOM is involved. The host drives `advance(seconds)` and reads `view()`.
// No upstream test imports this file; nothing checks this port beyond its gate compile.

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

import narchy.spacegraph.Vec3

data class ScopeForceOptions(
    /** Repulsion strength between bodies in the same scope. Default 2000. */
    val repulsion: Double? = null,
    /** Attraction along edges inside a scope. Default 0.02. */
    val attraction: Double? = null,
    /** Per-step heat decay; forces fade to zero so the system can sleep. Default 0.96. */
    val cooling: Double? = null,
    /** Acceleration cap, preventing explosions. Default 1200. */
    val maxAcceleration: Double? = null,
    /** Above this body count a scope skips pairwise forces. Default 120. */
    val maxBodies: Int? = null,
)

/** Where a new body starts: its target or the origin of its owning scope. */
enum class ScopeSpawn { TARGET, ORIGIN }

data class ScopeSimulationOptions(
    /** Spring stiffness toward the solved target. Default 140. */
    val stiffness: Double? = null,
    /** Damping ratio: 1 is critically damped, below 1 overshoots. Default 1. */
    val dampingRatio: Double? = null,
    /** Body mass. Default 1. */
    val mass: Double? = null,
    /** Fixed integration step in seconds. Default 1/60. */
    val step: Double? = null,
    /** Maximum integration steps in one `advance`. Default 8. */
    val maxSteps: Int? = null,
    /** Default ORIGIN. */
    val spawn: ScopeSpawn? = null,
    /** A body sleeps when it is this close to its target and this slow. */
    val sleepDistance: Double? = null,
    val sleepSpeed: Double? = null,
    /** Snap to targets immediately, for reduced-motion or headless use. */
    val instant: Boolean? = null,
    /** Qualified ids held at their solved target. */
    val pinned: Iterable<String>? = null,
    /** Enable transient inter-body forces. Off by default; bodies then only chase their targets. */
    val forces: ScopeForceOptions? = null,
    /** Options for the underlying scope solver. */
    val layout: ScopeLayoutOptions? = null,
)

private fun zero(): Position = Vec3(0.0, 0.0, 0.0)

private fun moved(from: Position, to: Position): Boolean =
    abs(from.x - to.x) > 1e-9 || abs(from.y - to.y) > 1e-9 || abs(from.z - to.z) > 1e-9

/** Override solved world positions with external targets and refit the reported bounds. */
private fun withTargets(layout: ScopeLayout, targets: Map<String, Position>): ScopeLayout {
    val positions = LinkedHashMap<String, Position>()
    for ((id, position) in layout.positions) positions[id] = targets[id] ?: position
    var minX = Double.POSITIVE_INFINITY; var minY = Double.POSITIVE_INFINITY
    var maxX = Double.NEGATIVE_INFINITY; var maxY = Double.NEGATIVE_INFINITY
    for (position in positions.values) {
        minX = minOf(minX, position.x)
        minY = minOf(minY, position.y)
        maxX = maxOf(maxX, position.x)
        maxY = maxOf(maxY, position.y)
    }
    val bounds = if (minX.isFinite()) boundsFromExtents(minX, minY, 0.0, maxX, maxY, 0.0) else layout.bounds
    return ScopeLayout(positions, layout.locals, layout.frames, layout.edges, bounds)
}

/** One set of spring bodies: node positions or scope-frame origins. */
private class BodySet<T> {
    val current = LinkedHashMap<String, Position>()
    val velocity = LinkedHashMap<String, Position>()
    val sleeping = LinkedHashSet<String>()

    val energy: Double
        get() {
            var total = 0.0
            for (v in velocity.values) total += v.x * v.x + v.y * v.y + v.z * v.z
            return total
        }

    fun settled(targets: Map<String, T>): Boolean = targets.keys.all { it in sleeping }

    fun snapshot(): Map<String, Position> = LinkedHashMap(current)

    fun clear() {
        current.clear(); velocity.clear(); sleeping.clear()
    }

    /** Seed missing bodies, wake those whose target moved, and drop bodies with no target. */
    fun sync(
        targets: Map<String, T>,
        previous: Map<String, T>?,
        goalOf: (T) -> Position,
        spawn: (String, T) -> Position,
    ) {
        for ((id, value) in targets) {
            val goal = goalOf(value)
            if (!current.containsKey(id)) {
                current[id] = spawn(id, value)
                velocity[id] = zero()
                sleeping.add(id)
            }
            val before = previous?.get(id)
            if (before == null || moved(goalOf(before), goal)) sleeping.remove(id)
        }
        for (id in current.keys.toList()) {
            if (!targets.containsKey(id)) drop(id)
        }
    }

    fun pin(id: String, goal: Position) {
        current[id] = goal
        velocity[id] = zero()
        sleeping.add(id)
    }

    fun unpin(id: String) {
        sleeping.remove(id)
    }

    fun settle(targets: Map<String, T>, goalOf: (T) -> Position) {
        for ((id, value) in targets) pin(id, goalOf(value))
    }

    private fun drop(id: String) {
        current.remove(id); velocity.remove(id); sleeping.remove(id)
    }
}

class ScopeSimulation(initial: ScopeSimulationOptions = ScopeSimulationOptions()) {
    private var options: ScopeSimulationOptions = initial
    private var host: ScopeHost = ScopeHost(initial.layout ?: ScopeLayoutOptions())
    private var specs: List<ScopeSpec>? = null
    private var signals: ScopeSignals = ScopeSignals()
    private var target: ScopeLayout? = null
    private var tree: ScopeTree? = null

    private val bodies = BodySet<Position>()
    private val frames = BodySet<ScopeFrame>()
    private val pinnedIds = LinkedHashSet<String>(initial.pinned?.toList().orEmpty())
    private var policyPinned: Set<String> = emptySet()
    private var layoutOverride: ScopeLayoutOptions = ScopeLayoutOptions()

    /** Externally solved world-space targets by qualified id; the springs chase these when set. */
    private var targets: Map<String, Position>? = null
    private var heat = 0.0
    private var accumulator = 0.0
    private var stepCount = 0

    val steps: Int get() = stepCount

    val settled: Boolean
        get() {
            val t = target ?: return true
            return bodies.settled(t.positions) && frames.settled(t.frames)
        }

    val energy: Double get() = bodies.energy + frames.energy

    val positions: Map<String, Position> get() = bodies.current

    /** The solved layout the springs chase, including scope frames, for read-only overlay use. */
    val targetLayout: ScopeLayout? get() = target

    val pinned: Set<String> get() = pinnedIds

    /** Transient force energy, decaying from 1 to 0 after a target change. */
    val heatLevel: Double get() = heat

    /** Replace the target graph. Existing bodies keep their motion; new bodies spawn at their scope. */
    fun setSpecs(newSpecs: List<ScopeSpec>, newSignals: ScopeSignals = ScopeSignals()) {
        specs = newSpecs
        signals = newSignals
        val hosted = host.update(newSpecs, newSignals, layoutOverride)
        val previous = target
        val next = targets?.takeIf { it.isNotEmpty() }?.let { withTargets(hosted.layout, it) } ?: hosted.layout
        target = next
        tree = hosted.tree

        val spawnAtOrigin = options.spawn != ScopeSpawn.TARGET
        bodies.sync(
            hosted.layout.positions,
            previous?.positions,
            { it },
            { id, position ->
                val path = tree?.let { scopeOf(it, id) }
                val origin = path?.let { hosted.layout.frames[it]?.origin }
                if (spawnAtOrigin && origin != null) origin else position
            },
        )
        frames.sync(
            hosted.layout.frames,
            previous?.frames,
            { it.origin },
            { _, frame ->
                val parentOrigin = frame.parent?.let { parent ->
                    frames.current[parent] ?: hosted.layout.frames[parent]?.origin
                }
                parentOrigin ?: frame.origin
            },
        )

        if (options.forces != null) {
            heat = 1.0
            bodies.sleeping.clear()
            frames.sleeping.clear()
        }
        if (options.instant == true) settle()
    }

    /**
     * Feed solved world-space positions as the target of the springs. The simulation stays the only
     * authority over current positions; `null` returns to the scope solver's own targets.
     */
    fun setLayoutTargets(newTargets: Map<String, Position>?) {
        targets = newTargets
        specs?.let { setSpecs(it, signals) }
    }

    /** Replace the base layout options and retarget without rebuilding the simulation. */
    fun setBaseLayout(layout: ScopeLayoutOptions) {
        options = options.copy(layout = mergeOptions(options.layout ?: ScopeLayoutOptions(), layout))
        layoutOverride = options.layout ?: ScopeLayoutOptions()
        specs?.let { setSpecs(it, signals) }
    }

    /** Replace transient force parameters and wake settled bodies so the change is visible. */
    fun setForces(forces: ScopeForceOptions) {
        val base = options.forces ?: ScopeForceOptions()
        options = options.copy(
            forces = ScopeForceOptions(
                repulsion = forces.repulsion ?: base.repulsion,
                attraction = forces.attraction ?: base.attraction,
                cooling = forces.cooling ?: base.cooling,
                maxAcceleration = forces.maxAcceleration ?: base.maxAcceleration,
                maxBodies = forces.maxBodies ?: base.maxBodies,
            ),
        )
        heat = 1.0
        bodies.sleeping.clear()
        frames.sleeping.clear()
        specs?.let { setSpecs(it, signals) }
    }

    /** Re-evaluate activation and layout with the last specs. */
    fun setSignals(newSignals: ScopeSignals) {
        specs?.let { setSpecs(it, newSignals) }
    }

    /** Apply a vision/focus policy: retarget, hold the focus, and resize the solvers. */
    fun setPolicy(input: ScopePolicyInput) {
        val currentTree = tree ?: return
        val currentSpecs = specs ?: return
        val policy = scopePolicy(currentTree, input, options.layout ?: ScopeLayoutOptions())
        layoutOverride = policy.layout
        setSpecs(currentSpecs, ScopeSignals(band = input.band, focus = input.focus, maxDepth = input.maxDepth))
        unpin(policyPinned)
        policyPinned = policy.pinned
        pin(policy.pinned)
    }

    fun pin(ids: Iterable<String>) {
        for (id in ids) {
            pinnedIds.add(id)
            target?.positions?.get(id)?.let { bodies.pin(id, it) }
            target?.frames?.get(id)?.let { frames.pin(id, it.origin) }
        }
    }

    fun unpin(ids: Iterable<String>) {
        for (id in ids) {
            pinnedIds.remove(id)
            bodies.unpin(id)
            frames.unpin(id)
        }
    }

    /** Advance by wall-clock seconds, using fixed steps and a cap to avoid a catch-up spiral. */
    fun advance(seconds: Double) {
        val step = options.step ?: (1.0 / 60.0)
        val maxSteps = options.maxSteps ?: 8
        accumulator += max(0.0, seconds)
        var executed = 0
        while (accumulator >= step && executed < maxSteps) {
            integrate(step)
            accumulator -= step
            stepCount++
            executed++
        }
        if (executed == maxSteps) accumulator = 0.0
    }

    /** Switch between animated and instant mode. */
    fun setInstant(value: Boolean) {
        options = options.copy(instant = value)
        if (value) settle()
    }

    /** Snap every body to its target and stop all motion. */
    fun settle() {
        val t = target ?: return
        bodies.settle(t.positions) { it }
        frames.settle(t.frames) { it.origin }
        heat = 0.0
        accumulator = 0.0
    }

    fun reset() {
        host = ScopeHost(options.layout ?: ScopeLayoutOptions())
        target = null
        tree = null
        specs = null
        bodies.clear()
        frames.clear()
        pinnedIds.clear()
        policyPinned = emptySet()
        layoutOverride = ScopeLayoutOptions()
        targets = null
        heat = 0.0
        accumulator = 0.0
        stepCount = 0
    }

    /** The current animated frame, compatible with the static `ScopeLayout` surface. */
    fun view(): ScopeLayout {
        val t = target
        val currentTree = tree
        check(t != null && currentTree != null) { "ScopeSimulation has no target; call setSpecs first" }
        val positionsNow = bodies.snapshot()
        val framesNow = LinkedHashMap<String, ScopeFrame>()
        for ((id, frame) in t.frames) {
            framesNow[id] = frame.copy(origin = frames.current[id] ?: frame.origin)
        }

        val edges = t.edges.map { edge ->
            edge.copy(
                from = positionsNow[edge.source] ?: framesNow[edge.source]?.origin ?: edge.from,
                to = positionsNow[edge.target] ?: framesNow[edge.target]?.origin ?: edge.to,
            )
        }

        val locals = LinkedHashMap<String, Position>()
        for ((id, position) in positionsNow) {
            val path = scopeOf(currentTree, id)
            val frame = path?.let { framesNow[it] }
            if (frame == null) {
                locals[id] = Vec3(position.x, position.y, position.z)
                continue
            }
            locals[id] = Vec3(
                (position.x - frame.origin.x) / frame.scale,
                (position.y - frame.origin.y) / frame.scale,
                (position.z - frame.origin.z) / frame.scale,
            )
        }

        // Bounds stay at the target so the viewport never jitters during a transition.
        return ScopeLayout(positionsNow, locals, framesNow, edges, t.bounds)
    }

    private fun integrate(dt: Double) {
        val t = target ?: return
        val stiffness = options.stiffness ?: 140.0
        val mass = options.mass ?: 1.0
        val damping = 2 * sqrt(stiffness * mass) * (options.dampingRatio ?: 1.0)
        val maxAcceleration = options.forces?.maxAcceleration ?: 1200.0
        val forces = if (options.forces != null) computeForces() else null
        val frameTargets = LinkedHashMap<String, Position>()
        for ((id, frame) in t.frames) frameTargets[id] = frame.origin

        relax(bodies, t.positions, dt, stiffness, damping, mass, forces, maxAcceleration)
        relax(frames, frameTargets, dt, stiffness, damping, mass, forces, maxAcceleration)
        if (forces != null) {
            heat *= options.forces?.cooling ?: 0.96
            if (heat < 0.01) heat = 0.0
        }
    }

    /** Transient pairwise forces inside each scope. Heat fades them out so the graph can sleep. */
    private fun computeForces(): Map<String, Position> {
        val accumulated = LinkedHashMap<String, DoubleArray>()
        val t = target ?: return emptyMap()
        val currentTree = tree ?: return emptyMap()
        val repulsion = options.forces?.repulsion ?: 2000.0
        val attraction = options.forces?.attraction ?: 0.02
        val maxAcceleration = options.forces?.maxAcceleration ?: 1200.0
        val maxBodies = options.forces?.maxBodies ?: 120

        fun add(id: String, x: Double, y: Double, z: Double) {
            val force = accumulated.getOrPut(id) { DoubleArray(3) }
            force[0] += x
            force[1] += y
            force[2] += z
        }

        for ((path, entry) in currentTree.byPath) {
            val frame = t.frames[path] ?: continue
            val scale = if (frame.scale == 0.0) 1.0 else frame.scale
            val bodiesHere = ArrayList<Body>()

            for (node in entry.nodes) {
                val id = "$path/${node.id}"
                val position = bodies.current[id] ?: continue
                val (width, height) = node.size ?: Pair(120.0, 64.0)
                bodiesHere.add(Body(id, position, (max(width, height) / 2) * scale))
            }
            for (child in entry.children) {
                val childFrame = t.frames[child.path] ?: continue
                bodiesHere.add(
                    Body(
                        child.path,
                        frames.current[child.path] ?: childFrame.origin,
                        (max(childFrame.bounds.size[0], childFrame.bounds.size[1]) / 2) * scale,
                    )
                )
            }
            if (bodiesHere.size > maxBodies) continue

            for (i in bodiesHere.indices) {
                for (j in i + 1 until bodiesHere.size) {
                    val a = bodiesHere[i]
                    val b = bodiesHere[j]
                    val dx = a.position.x - b.position.x
                    val dy = a.position.y - b.position.y
                    val dz = a.position.z - b.position.z
                    val rawDistance = sqrt(dx * dx + dy * dy + dz * dz)
                    val distance = if (rawDistance == 0.0) 1e-3 else rawDistance
                    val effective = max(distance, a.radius + b.radius)
                    val force = min(repulsion / (effective * effective), maxAcceleration)
                    val fx = (dx / distance) * force
                    val fy = (dy / distance) * force
                    val fz = (dz / distance) * force
                    add(a.id, fx, fy, fz)
                    add(b.id, -fx, -fy, -fz)
                }
            }

            val members = HashMap<String, Body>()
            for (body in bodiesHere) members[body.id] = body
            for (edge in entry.edges) {
                val source = members[resolveScopeRef(currentTree, path, edge.source)] ?: continue
                val target = members[resolveScopeRef(currentTree, path, edge.target)] ?: continue
                val dx = target.position.x - source.position.x
                val dy = target.position.y - source.position.y
                val dz = target.position.z - source.position.z
                add(source.id, dx * attraction, dy * attraction, dz * attraction)
                add(target.id, -dx * attraction, -dy * attraction, -dz * attraction)
            }
        }
        val result = LinkedHashMap<String, Position>()
        for ((id, force) in accumulated) result[id] = Vec3(force[0], force[1], force[2])
        return result
    }

    private fun relax(
        set: BodySet<*>,
        goals: Map<String, Position>,
        dt: Double,
        stiffness: Double,
        damping: Double,
        mass: Double,
        forces: Map<String, Position>?,
        maxAcceleration: Double,
    ) {
        val sleepDistance = options.sleepDistance ?: 0.01
        val sleepSpeed = options.sleepSpeed ?: 0.01

        for ((id, goal) in goals) {
            if (pinnedIds.contains(id)) {
                set.pin(id, goal)
                continue
            }
            if (set.sleeping.contains(id)) continue

            val start = set.current[id] ?: goal
            val position = doubleArrayOf(start.x, start.y, start.z)
            val speedStart = set.velocity[id] ?: zero()
            val speed = doubleArrayOf(speedStart.x, speedStart.y, speedStart.z)
            val force = forces?.get(id)
            val forceAcceleration = doubleArrayOf(
                (force?.x ?: 0.0) * heat,
                (force?.y ?: 0.0) * heat,
                (force?.z ?: 0.0) * heat,
            )
            val magnitude = sqrt(
                forceAcceleration[0] * forceAcceleration[0] +
                    forceAcceleration[1] * forceAcceleration[1] +
                    forceAcceleration[2] * forceAcceleration[2]
            )
            val scale = if (magnitude > maxAcceleration) maxAcceleration / magnitude else 1.0
            val g = doubleArrayOf(goal.x, goal.y, goal.z)

            var awake = false
            for (axis in 0..2) {
                val acceleration =
                    (stiffness * (g[axis] - position[axis]) - damping * speed[axis]) / mass +
                        forceAcceleration[axis] * scale
                speed[axis] += acceleration * dt
                position[axis] += speed[axis] * dt
                if (abs(speed[axis]) > sleepSpeed || abs(g[axis] - position[axis]) > sleepDistance) {
                    awake = true
                }
            }
            if (awake) {
                set.current[id] = Vec3(position[0], position[1], position[2])
                set.velocity[id] = Vec3(speed[0], speed[1], speed[2])
            } else {
                set.pin(id, goal)
            }
        }
    }

    private class Body(val id: String, val position: Position, val radius: Double)
}
