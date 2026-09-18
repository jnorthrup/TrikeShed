package borg.trikeshed.windowtoolkit.math

import kotlin.jvm.JvmName

// Basic multiplatform implementations for primitives where we don't have trikeshed lib access in commonMain

interface Join<A, B> {
    val a: A
    val b: B
    operator fun component1(): A = a
    operator fun component2(): B = b
}

inline infix fun <A, B> A.j(b: B): Join<A, B> = object : Join<A, B> {
    override val a: A get() = this@j
    override val b: B get() = b
}

typealias Twin<T> = Join<T, T>
typealias Series<T> = Join<Int, (Int) -> T>

val <T> Series<T>.size: Int get() = a
operator fun <T> Series<T>.get(i: Int): T = b(i)

inline infix fun <X, C> Series<X>.α(crossinline xform: (X) -> C): Series<C> =
    size j { i -> xform(this[i]) }

// Geometric primitives using Twin/Join/Series

typealias Vec2 = Twin<Double>
typealias Vec3 = Join<Vec2, Double>
typealias Vec4 = Twin<Vec2>

// Helper extensions for readable geometry

@get:JvmName("getVec2X")
val Vec2.x: Double get() = a
@get:JvmName("getVec2Y")
val Vec2.y: Double get() = b

@get:JvmName("getVec3X")
val Vec3.x: Double get() = a.a
@get:JvmName("getVec3Y")
val Vec3.y: Double get() = a.b
@get:JvmName("getVec3Z")
val Vec3.z: Double get() = b
@get:JvmName("getVec3XY")
val Vec3.xy: Vec2 get() = a

@get:JvmName("getVec4X")
val Vec4.x: Double get() = a.a
@get:JvmName("getVec4Y")
val Vec4.y: Double get() = a.b
@get:JvmName("getVec4Z")
val Vec4.z: Double get() = b.a
@get:JvmName("getVec4W")
val Vec4.w: Double get() = b.b
@get:JvmName("getVec4XY")
val Vec4.xy: Vec2 get() = a
@get:JvmName("getVec4ZW")
val Vec4.zw: Vec2 get() = b

fun v2(x: Double, y: Double): Vec2 = x j y
fun v3(x: Double, y: Double, z: Double): Vec3 = v2(x, y) j z
fun v4(x: Double, y: Double, z: Double, w: Double): Vec4 = v2(x, y) j v2(z, w)

// Vector arithmetic Kernels (Pure Math Operations)

@JvmName("plusVec2")
infix fun Vec2.plus(other: Vec2): Vec2 = (x + other.x) j (y + other.y)
@JvmName("minusVec2")
infix fun Vec2.minus(other: Vec2): Vec2 = (x - other.x) j (y - other.y)
@JvmName("timesVec2")
fun Vec2.times(scalar: Double): Vec2 = (x * scalar) j (y * scalar)

@JvmName("plusVec3")
infix fun Vec3.plus(other: Vec3): Vec3 = v3(x + other.x, y + other.y, z + other.z)
@JvmName("minusVec3")
infix fun Vec3.minus(other: Vec3): Vec3 = v3(x - other.x, y - other.y, z - other.z)
@JvmName("timesVec3")
fun Vec3.times(scalar: Double): Vec3 = v3(x * scalar, y * scalar, z * scalar)

@JvmName("plusVec4")
infix fun Vec4.plus(other: Vec4): Vec4 = v4(x + other.x, y + other.y, z + other.z, w + other.w)

// Transform Kernels on Datagrids (Series<VecN>)

/**
 * Applies a 2D translation to a Series of Vec2 coordinates.
 */
fun Series<Vec2>.translate(offset: Vec2): Series<Vec2> = this α { it plus offset }

/**
 * Applies a 2D scaling to a Series of Vec2 coordinates.
 */
fun Series<Vec2>.scale(scalar: Double): Series<Vec2> = this α { it.times(scalar) }

/**
 * Applies a 2D affine transform.
 * (x, y) -> (ax + by + tx, cx + dy + ty)
 */
fun Series<Vec2>.affine(a: Double, b: Double, c: Double, d: Double, tx: Double, ty: Double): Series<Vec2> =
    this α { v: Vec2 ->
        v2(
            a * v.x + b * v.y + tx,
            c * v.x + d * v.y + ty
        )
    }

/**
 * Transform 3D points by a 4x4 projection matrix.
 */
fun Series<Vec3>.project(matrix: Series<Double>): Series<Vec3> {
    require(matrix.size == 16) { "Projection matrix must be 4x4 (16 elements)" }
    return this α { v: Vec3 ->
        val m = matrix
        val w = m[3] * v.x + m[7] * v.y + m[11] * v.z + m[15]
        val invW = if (w != 0.0) 1.0 / w else 1.0
        v3(
            (m[0] * v.x + m[4] * v.y + m[8] * v.z + m[12]) * invW,
            (m[1] * v.x + m[5] * v.y + m[9] * v.z + m[13]) * invW,
            (m[2] * v.x + m[6] * v.y + m[10] * v.z + m[14]) * invW
        )
    }
}
