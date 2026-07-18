package borg.trikeshed.manimwm.manim

open class Line(val start: Point, val end: Point) : VMobject()

open class Circle(val radius: Double = 1.0) : VMobject()

open class Rectangle(val width: Double = 2.0, val height: Double = 1.0) : VMobject()

open class Dot(val point: Point = pt(0.0, 0.0), val radius: Double = 0.08) : VMobject() {
    init {
        moveTo(point)
    }
}

class Arrow(start: Point, end: Point) : Line(start, end)

class RegularPolygon(val n: Int) : VMobject()

class Triangle : VMobject()

class Square(side_length: Double = 2.0) : Rectangle(side_length, side_length)

class SurroundingRectangle(mobject: Mobject, buff: Double = 0.1) : Rectangle()

class Brace(mobject: Mobject, direction: Point = DOWN.vector, buff: Double = 0.2) : VMobject()

class Arc(val radius: Double = 1.0, val angle: Double = 1.0) : VMobject()
