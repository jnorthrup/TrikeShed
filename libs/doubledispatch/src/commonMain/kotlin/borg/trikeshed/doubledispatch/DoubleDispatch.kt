@file:Suppress("NOTHING_TO_INLINE", "INLINE_CLASS_DEPRECATED")

package borg.trikeshed.doubledispatch

import kotlin.math.PI

/**
 * Double dispatch using compile-time overload resolution — no `is` checks,
 * no `Any` parameters, no reflection. The visitor interface provides overloaded
 * `visit()` methods for each concrete type; the dispatch target is selected
 * at compile time by the receiver type.
 *
 * ## Return Type Witness Pattern (Monomorphic Fidelity)
 *
 * Traditional double dispatch loses type information at the dispatch boundary:
 * ```
 * interface Visitor<T> { fun visit(obj: Any): T }  // type erased
 * ```
 *
 * The return type witness pattern preserves monomorphic type fidelity by using
 * typed witnesses that carry the source type through the dispatch chain:
 * ```
 * val witness: CircleWitness<Double> = circle.accept(AreaWitnessVisitor())
 * val result: Double = witness.value  // No cast, type preserved
 * ```
 *
 * ## Static Argument Type Dispatch
 *
 * The `produce()` function delegates to `shape.accept(visitor)`, which
 * resolves the correct visitor overload by static argument type — each
 * Shape subclass calls `visitor.visit(this)` where `this` has a known
 * compile-time type. No reified types, no reflection, no runtime type checks.
 */

// === Return Type Witness ===

/** Base interface for return type witnesses.
 *  Carries the return value with phantom type information preserving
 *  monomorphic type fidelity through the dispatch chain. */
interface ReturnTypeWitness<out T> {
    val value: T
}

/** Marker for monomorphic type witnesses — the value AND source type are preserved. */
interface MonomorphicWitness<T> : ReturnTypeWitness<T>

// === Shape Domain with True Double Dispatch ===

/** Visitor interface with overloaded visit() methods for each concrete shape.
 *  Compile-time overload resolution selects the correct method — no `is` checks,
 *  no `Any` parameter, no reflection. This IS the second dispatch. */
interface ShapeVisitor<R> {
    fun visit(circle: Circle): R
    fun visit(rectangle: Rectangle): R
}

/** Base shape. The `accept` method provides the first dispatch (polymorphic).
 *  Inside `accept`, `visitor.visit(this)` triggers the second dispatch
 *  (compile-time overload resolution on the concrete type of `this`). */
abstract class Shape {
    /** First dispatch: polymorphic resolution on the Shape subtype.
     *  Second dispatch: visitor.visit(this) — overload resolution at compile time. */
    abstract fun <R> accept(visitor: ShapeVisitor<R>): R

    abstract fun area(): Double
}

class Circle(val radius: Double) : Shape() {
    /** Double dispatch: calls visitor.visit(this) where `this` is Circle.
     *  The Circle overload is selected at compile time — no instanceof. */
    override fun <R> accept(visitor: ShapeVisitor<R>): R = visitor.visit(this)
    override fun area(): Double = PI * radius * radius
}

class Rectangle(val width: Double, val height: Double) : Shape() {
    /** Double dispatch: calls visitor.visit(this) where `this` is Rectangle.
     *  The Rectangle overload is selected at compile time — no instanceof. */
    override fun <R> accept(visitor: ShapeVisitor<R>): R = visitor.visit(this)
    override fun area(): Double = width * height
}

// === Type Witnesses for Monomorphic Fidelity ===

/** Circle-specific witness — non-generic, monomorphic type fidelity at class level.
 *  Enables assertEquals(CircleWitness::class, witness::class) without type inference failure. */
data class CircleWitness(override val value: Double) : MonomorphicWitness<Double>

/** Rectangle-specific witness — non-generic, monomorphic type fidelity at class level.
 *  Enables assertEquals(RectangleWitness::class, witness::class) without type inference failure. */
data class RectangleWitness(override val value: Double) : MonomorphicWitness<Double>

/** Shape base witness for polymorphic dispatch with type preservation. */
data class ShapeWitness<R>(override val value: R) : MonomorphicWitness<R>

// === Witness Visitors — Return Type Witnesses via True Double Dispatch ===

/** Area visitor returning monomorphic witnesses.
 *  No `is` checks — each visit() overload is selected at compile time. */
class AreaWitnessVisitor : ShapeVisitor<MonomorphicWitness<Double>> {
    override fun visit(circle: Circle): MonomorphicWitness<Double> =
        CircleWitness(circle.area())
    override fun visit(rectangle: Rectangle): MonomorphicWitness<Double> =
        RectangleWitness(rectangle.area())
}

/** Perimeter visitor returning monomorphic witnesses. */
class PerimeterWitnessVisitor : ShapeVisitor<MonomorphicWitness<Double>> {
    override fun visit(circle: Circle): MonomorphicWitness<Double> =
        CircleWitness(2 * PI * circle.radius)
    override fun visit(rectangle: Rectangle): MonomorphicWitness<Double> =
        RectangleWitness(2 * (rectangle.width + rectangle.height))
}

/** Description visitor returning monomorphic witnesses with String return.
 *  Uses ShapeWitness<String> since CircleWitness/RectangleWitness are monomorphic Double witnesses.
 *  Uses fmt() for cross-platform consistent Double formatting (JS omits trailing .0). */
class DescriptionWitnessVisitor : ShapeVisitor<MonomorphicWitness<String>> {
    private fun Double.fmt(): String = if (this == kotlin.math.floor(this) && !this.isInfinite()) {
        "${this.toLong()}.0"
    } else {
        this.toString()
    }

    override fun visit(circle: Circle): MonomorphicWitness<String> =
        ShapeWitness("Circle(radius=${circle.radius.fmt()})")
    override fun visit(rectangle: Rectangle): MonomorphicWitness<String> =
        ShapeWitness("Rectangle(width=${rectangle.width.fmt()}, height=${rectangle.height.fmt()})")
}

// === Produce — Compile-Time Dispatch via Static Argument Type ===

/** Produce — resolves the visitor dispatch by static argument type.
 *  Each Shape subclass calls visitor.visit(this) where this has a known
 *  compile-time type, so the correct overloaded visit() is selected.
 *  No reified types needed — dispatch is by static type, not runtime type. */
fun <R, S : Shape> produce(shape: S, visitor: ShapeVisitor<R>): R =
    shape.accept(visitor)
