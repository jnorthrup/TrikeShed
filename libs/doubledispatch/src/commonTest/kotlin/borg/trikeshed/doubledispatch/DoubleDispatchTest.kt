package borg.trikeshed.doubledispatch

import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Tests for double dispatch using compile-time overload resolution.
 * No `is` checks, no `Any` parameters, no reflection.
 * True double dispatch: polymorphic accept() + overloaded visit().
 */
class DoubleDispatchTest {

    /** Area visitor using true double dispatch — overloaded visit(), no is-checks. */
    class AreaVisitor : ShapeVisitor<Double> {
        override fun visit(circle: Circle): Double = circle.area()
        override fun visit(rectangle: Rectangle): Double = rectangle.area()
    }

    @Test
    fun testCircleArea() {
        val circle = Circle(5.0)
        val visitor = AreaVisitor()
        val area = circle.accept(visitor)
        assertEquals(PI * 25.0, area, 0.001)
    }

    @Test
    fun testRectangleArea() {
        val rectangle = Rectangle(4.0, 6.0)
        val visitor = AreaVisitor()
        val area = rectangle.accept(visitor)
        assertEquals(24.0, area, 0.001)
    }

    @Test
    fun testDoubleDispatchPolymorphism() {
        val shapes: List<Shape> = listOf(
            Circle(2.0),
            Rectangle(3.0, 4.0),
            Circle(1.5)
        )

        val visitor = AreaVisitor()
        val areas = shapes.map { it.accept(visitor) }

        assertEquals(3, areas.size)
        assertEquals(PI * 4.0, areas[0], 0.001)
        assertEquals(12.0, areas[1], 0.001)
        assertEquals(PI * 2.25, areas[2], 0.001)
    }

    @Test
    fun testWitnessMonomorphicFidelity() {
        val circle = Circle(3.0)
        val rectangle = Rectangle(4.0, 5.0)

        // AreaWitnessVisitor returns MonomorphicWitness<Double>
        // Each visit() returns the shape-specific witness type
        val visitor = AreaWitnessVisitor()

        val circleWitness = circle.accept(visitor)
        assertEquals(CircleWitness::class, circleWitness::class)
        assertEquals(PI * 9.0, circleWitness.value, 0.001)

        val rectWitness = rectangle.accept(visitor)
        assertEquals(RectangleWitness::class, rectWitness::class)
        assertEquals(20.0, rectWitness.value, 0.001)
    }

    @Test
    fun testPerimeterWitnessVisitor() {
        val circle = Circle(1.0)
        val rectangle = Rectangle(3.0, 4.0)

        val visitor = PerimeterWitnessVisitor()

        val circleWitness = circle.accept(visitor)
        assertEquals(2 * PI, circleWitness.value, 0.001)

        val rectWitness = rectangle.accept(visitor)
        assertEquals(14.0, rectWitness.value, 0.001)
    }

    @Test
    fun testDescriptionWitnessVisitor() {
        val circle = Circle(2.5)
        val rectangle = Rectangle(3.5, 4.5)

        val visitor = DescriptionWitnessVisitor()

        val circleDesc = circle.accept(visitor)
        assertEquals("Circle(radius=2.5)", circleDesc.value)

        val rectDesc = rectangle.accept(visitor)
        assertEquals("Rectangle(width=3.5, height=4.5)", rectDesc.value)
    }

    @Test
    fun testInlineReifiedProduce() {
        val circle = Circle(5.0)
        val visitor = AreaVisitor()

        // Produce — dispatch by static argument type, no shim pointer
        val area = produce(circle, visitor)
        assertEquals(PI * 25.0, area, 0.001)
    }
}


