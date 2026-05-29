package borg.trikeshed.doubledispatch

import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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

/**
 * Tests for the JSON parser with compile-time dispatched handle<R, reified T>.
 * Covers all 6 JSON value types: String, Number, Boolean, Object, Array, Null.
 */
class JsonDispatchTest {

    /** Counting factory that records which reify() overload was called. */
    class CountingFactory : Factory<String> {
        var stringCalls = 0
        var numberCalls = 0
        var booleanCalls = 0
        var nullCalls = 0
        var objectCalls = 0
        var arrayCalls = 0

        override fun reify(value: String, at: Region): String { stringCalls++; return value }
        override fun reify(value: Number, at: Region): String { numberCalls++; return value.toString() }
        override fun reify(value: Boolean, at: Region): String { booleanCalls++; return value.toString() }
        override fun reify(value: Nothing?, at: Region): String { nullCalls++; return "null" }
        override fun reify(value: Obj<String>, at: Region): String { objectCalls++; return "{}" }
        override fun reify(value: Arr<String>, at: Region): String { arrayCalls++; return "[]" }
    }

    // === String dispatch ===

    @Test
    fun testStringParsing() {
        val parser = Parser("\"hello world\"")
        val reifier = parser.parseDepth0<String>()
        val result = reifier.force(JsonFactory)
        assertEquals("hello world", result)
    }

    @Test
    fun testStringWithEscapes() {
        val parser = Parser("\"hello\\nworld\\t!\"")
        val reifier = parser.parseDepth0<String>()
        val result = reifier.force(JsonFactory)
        assertEquals("hello\nworld\t!", result)
    }

    @Test
    fun testStringDispatchesToCorrectFactory() {
        val parser = Parser("\"test\"")
        val reifier = parser.parseDepth0<String>()
        val factory = CountingFactory()
        reifier.force(factory)
        assertEquals(1, factory.stringCalls)
        assertEquals(0, factory.numberCalls)
    }

    // === Number dispatch ===

    @Test
    fun testIntegerParsing() {
        val parser = Parser("42")
        val reifier = parser.parseDepth0<String>()
        val result = reifier.force(JsonFactory)
        assertEquals("42", result)
    }

    @Test
    fun testNegativeIntegerParsing() {
        val parser = Parser("-7")
        val reifier = parser.parseDepth0<String>()
        val result = reifier.force(JsonFactory)
        assertEquals("-7", result)
    }

    @Test
    fun testFloatParsing() {
        val parser = Parser("3.14")
        val reifier = parser.parseDepth0<String>()
        val result = reifier.force(JsonFactory)
        assertEquals("3.14", result)
    }

    @Test
    fun testExponentParsing() {
        val parser = Parser("1.5e10")
        val reifier = parser.parseDepth0<String>()
        val result = reifier.force(JsonFactory)
        assertEquals("1.5E10", result)
    }

    @Test
    fun testNumberDispatchesToCorrectFactory() {
        val parser = Parser("42")
        val reifier = parser.parseDepth0<String>()
        val factory = CountingFactory()
        reifier.force(factory)
        assertEquals(0, factory.stringCalls)
        assertEquals(1, factory.numberCalls)
    }

    // === Boolean dispatch ===

    @Test
    fun testTrueParsing() {
        val parser = Parser("true")
        val reifier = parser.parseDepth0<String>()
        val result = reifier.force(JsonFactory)
        assertEquals("true", result)
    }

    @Test
    fun testFalseParsing() {
        val parser = Parser("false")
        val reifier = parser.parseDepth0<String>()
        val result = reifier.force(JsonFactory)
        assertEquals("false", result)
    }

    @Test
    fun testBooleanDispatchesToCorrectFactory() {
        val parser = Parser("true")
        val reifier = parser.parseDepth0<String>()
        val factory = CountingFactory()
        reifier.force(factory)
        assertEquals(0, factory.stringCalls)
        assertEquals(1, factory.booleanCalls)
    }

    // === Null dispatch ===

    @Test
    fun testNullParsing() {
        val parser = Parser("null")
        val reifier = parser.parseDepth0<String>()
        val result = reifier.force(JsonFactory)
        assertEquals("null", result)
    }

    @Test
    fun testNullDispatchesToCorrectFactory() {
        val parser = Parser("null")
        val reifier = parser.parseDepth0<String>()
        val factory = CountingFactory()
        reifier.force(factory)
        assertEquals(0, factory.stringCalls)
        assertEquals(1, factory.nullCalls)
    }

    // === Object dispatch ===

    @Test
    fun testEmptyObjectParsing() {
        val parser = Parser("{}")
        val reifier = parser.parseDepth0<String>()
        val result = reifier.force(JsonFactory)
        assertEquals("{}", result)
    }

    @Test
    fun testSimpleObjectParsing() {
        val parser = Parser("{\"name\":\"Ada\"}")
        val reifier = parser.parseDepth0<String>()
        val result = reifier.force(JsonFactory)
        assertTrue(result.contains("\"name\""))
        assertTrue(result.contains("Ada"))
    }

    @Test
    fun testObjectDispatchesToCorrectFactory() {
        val parser = Parser("{}")
        val reifier = parser.parseDepth0<String>()
        val factory = CountingFactory()
        reifier.force(factory)
        assertEquals(1, factory.objectCalls)
        assertEquals(0, factory.arrayCalls)
    }

    // === Array dispatch ===

    @Test
    fun testEmptyArrayParsing() {
        val parser = Parser("[]")
        val reifier = parser.parseDepth0<String>()
        val result = reifier.force(JsonFactory)
        assertEquals("[]", result)
    }

    @Test
    fun testSimpleArrayParsing() {
        val parser = Parser("[1,2,3]")
        val reifier = parser.parseDepth0<String>()
        val result = reifier.force(JsonFactory)
        assertEquals("[1,2,3]", result)
    }

    @Test
    fun testArrayDispatchesToCorrectFactory() {
        val parser = Parser("[]")
        val reifier = parser.parseDepth0<String>()
        val factory = CountingFactory()
        reifier.force(factory)
        assertEquals(0, factory.objectCalls)
        assertEquals(1, factory.arrayCalls)
    }

    // === Mixed / nested ===

    @Test
    fun testComplexNestedJson() {
        val input = """{"name":"Ada","age":37,"ok":true,"xs":[1,null,"x"]}"""
        val parser = Parser(input)
        val reifier = parser.parseDepth0<String>()
        val result = reifier.force(JsonFactory)

        // Verify all parts are present in the reified output
        assertTrue(result.contains("\"name\""))
        assertTrue(result.contains("Ada"))
        assertTrue(result.contains("\"age\""))
        assertTrue(result.contains("37"))
        assertTrue(result.contains("\"ok\""))
        assertTrue(result.contains("true"))
        assertTrue(result.contains("\"xs\""))
        assertTrue(result.contains("[1,null,\"x\"]"))
    }

    @Test
    fun testMixedArrayTypes() {
        val parser = Parser("[\"hello\",42,true,null]")
        val reifier = parser.parseDepth0<String>()
        val result = reifier.force(JsonFactory)
        assertEquals("[hello,42,true,null]", result)
    }

    // === RegionScanner integration ===

    @Test
    fun testRegionScannerCollectsAllRegions() {
        val input = """{"a":1,"b":"two"}"""
        val scanner = RegionScanner<String>(input)
        val regions = scanner.scan()

        // Should have regions for: the object, number 1, string "two"
        assertTrue(regions.size >= 3)
    }

    @Test
    fun testRegionScannerDecodeAll() {
        val input = """{"x":42}"""
        val scanner = RegionScanner<String>(input)
        scanner.scan()
        val decoded = scanner.decodeAll(JsonFactory)

        // All regions should decode to non-null values
        assertTrue(decoded.isNotEmpty())
        decoded.values.forEach { value ->
            assertTrue(value.isNotEmpty())
        }
    }

    // === Forwarder / Recognizer pattern ===

    @Test
    fun testForwarderRecognizePattern() {
        // Test that the Forwarder -> Recognizer -> Reifier chain works
        val forwarder: Forwarder<String> = { "test-value" }
        val recognizer: Recognizer<String> = recognize(forwarder)

        // The recognizer strips KClass and forwards Region to Forwarder
        val result = recognizer(String::class, Region.of(0, 10))
        assertEquals("test-value", result)
    }

    @Test
    fun testForwarderCapturesValue() {
        var callCount = 0
        val forwarder: Forwarder<String> = { callCount++; "captured" }
        val recognizer: Recognizer<String> = recognize(forwarder)

        // Calling recognizer should invoke the forwarder
        recognizer(String::class, Region.of(0, 1))
        assertEquals(1, callCount)

        recognizer(String::class, Region.of(2, 3))
        assertEquals(2, callCount)
    }
}

/**
 * Tests for the JSON parser with compile-time dispatched handle<R, reified T>.
 * Covers all 6 JSON value types: String, Number, Boolean, Object, Array, Null.
 */
class JsonDispatchTest {

    /** Counting factory that records which reify() overload was called. */
    class CountingFactory : Factory<String> {
        var stringCalls = 0
        var numberCalls = 0
        var booleanCalls = 0
        var nullCalls = 0
        var objectCalls = 0
        var arrayCalls = 0

        override fun reify(value: String, at: Region): String { stringCalls++; return value }
        override fun reify(value: Number, at: Region): String { numberCalls++; return value.toString() }
        override fun reify(value: Boolean, at: Region): String { booleanCalls++; return value.toString() }
        override fun reify(value: Nothing?, at: Region): String { nullCalls++; return "null" }
        override fun reify(value: Obj<String>, at: Region): String { objectCalls++; return "{}" }
        override fun reify(value: Arr<String>, at: Region): String { arrayCalls++; return "[]" }
    }

    // === String dispatch ===

    @Test
    fun testStringParsing() {
        val parser = Parser("\"hello world\"")
        val reifier = parser.parseDepth0<String>()
        val result = reifier.force(JsonFactory)
        assertEquals("hello world", result)
    }

    @Test
    fun testStringWithEscapes() {
        val parser = Parser("\"hello\\nworld\\t!\"")
        val reifier = parser.parseDepth0<String>()
        val result = reifier.force(JsonFactory)
        assertEquals("hello\nworld\t!", result)
    }

    @Test
    fun testStringDispatchesToCorrectFactory() {
        val parser = Parser("\"test\"")
        val reifier = parser.parseDepth0<String>()
        val factory = CountingFactory()
        reifier.force(factory)
        assertEquals(1, factory.stringCalls)
        assertEquals(0, factory.numberCalls)
    }

    // === Number dispatch ===

    @Test
    fun testIntegerParsing() {
        val parser = Parser("42")
        val reifier = parser.parseDepth0<String>()
        val result = reifier.force(JsonFactory)
        assertEquals("42", result)
    }

    @Test
    fun testNegativeIntegerParsing() {
        val parser = Parser("-7")
        val reifier = parser.parseDepth0<String>()
        val result = reifier.force(JsonFactory)
        assertEquals("-7", result)
    }

    @Test
    fun testFloatParsing() {
        val parser = Parser("3.14")
        val reifier = parser.parseDepth0<String>()
        val result = reifier.force(JsonFactory)
        assertEquals("3.14", result)
    }

    @Test
    fun testExponentParsing() {
        val parser = Parser("1.5e10")
        val reifier = parser.parseDepth0<String>()
        val result = reifier.force(JsonFactory)
        assertEquals("1.5E10", result)
    }

    @Test
    fun testNumberDispatchesToCorrectFactory() {
        val parser = Parser("42")
        val reifier = parser.parseDepth0<String>()
        val factory = CountingFactory()
        reifier.force(factory)
        assertEquals(0, factory.stringCalls)
        assertEquals(1, factory.numberCalls)
    }

    // === Boolean dispatch ===

    @Test
    fun testTrueParsing() {
        val parser = Parser("true")
        val reifier = parser.parseDepth0<String>()
        val result = reifier.force(JsonFactory)
        assertEquals("true", result)
    }

    @Test
    fun testFalseParsing() {
        val parser = Parser("false")
        val reifier = parser.parseDepth0<String>()
        val result = reifier.force(JsonFactory)
        assertEquals("false", result)
    }

    @Test
    fun testBooleanDispatchesToCorrectFactory() {
        val parser = Parser("true")
        val reifier = parser.parseDepth0<String>()
        val factory = CountingFactory()
        reifier.force(factory)
        assertEquals(0, factory.stringCalls)
        assertEquals(1, factory.booleanCalls)
    }

    // === Null dispatch ===

    @Test
    fun testNullParsing() {
        val parser = Parser("null")
        val reifier = parser.parseDepth0<String>()
        val result = reifier.force(JsonFactory)
        assertEquals("null", result)
    }

    @Test
    fun testNullDispatchesToCorrectFactory() {
        val parser = Parser("null")
        val reifier = parser.parseDepth0<String>()
        val factory = CountingFactory()
        reifier.force(factory)
        assertEquals(0, factory.stringCalls)
        assertEquals(1, factory.nullCalls)
    }

    // === Object dispatch ===

    @Test
    fun testEmptyObjectParsing() {
        val parser = Parser("{}")
        val reifier = parser.parseDepth0<String>()
        val result = reifier.force(JsonFactory)
        assertEquals("{}", result)
    }

    @Test
    fun testSimpleObjectParsing() {
        val parser = Parser("{\"name\":\"Ada\"}")
        val reifier = parser.parseDepth0<String>()
        val result = reifier.force(JsonFactory)
        assertTrue(result.contains("\"name\""))
        assertTrue(result.contains("Ada"))
    }

    @Test
    fun testObjectDispatchesToCorrectFactory() {
        val parser = Parser("{}")
        val reifier = parser.parseDepth0<String>()
        val factory = CountingFactory()
        reifier.force(factory)
        assertEquals(1, factory.objectCalls)
        assertEquals(0, factory.arrayCalls)
    }

    // === Array dispatch ===

    @Test
    fun testEmptyArrayParsing() {
        val parser = Parser("[]")
        val reifier = parser.parseDepth0<String>()
        val result = reifier.force(JsonFactory)
        assertEquals("[]", result)
    }

    @Test
    fun testSimpleArrayParsing() {
        val parser = Parser("[1,2,3]")
        val reifier = parser.parseDepth0<String>()
        val result = reifier.force(JsonFactory)
        assertEquals("[1,2,3]", result)
    }

    @Test
    fun testArrayDispatchesToCorrectFactory() {
        val parser = Parser("[]")
        val reifier = parser.parseDepth0<String>()
        val factory = CountingFactory()
        reifier.force(factory)
        assertEquals(0, factory.objectCalls)
        assertEquals(1, factory.arrayCalls)
    }

    // === Mixed / nested ===

    @Test
    fun testComplexNestedJson() {
        val input = """{"name":"Ada","age":37,"ok":true,"xs":[1,null,"x"]}"""
        val parser = Parser(input)
        val reifier = parser.parseDepth0<String>()
        val result = reifier.force(JsonFactory)

        // Verify all parts are present in the reified output
        assertTrue(result.contains("\"name\""))
        assertTrue(result.contains("Ada"))
        assertTrue(result.contains("\"age\""))
        assertTrue(result.contains("37"))
        assertTrue(result.contains("\"ok\""))
        assertTrue(result.contains("true"))
        assertTrue(result.contains("\"xs\""))
        assertTrue(result.contains("[1,null,\"x\"]"))
    }

    @Test
    fun testMixedArrayTypes() {
        val parser = Parser("[\"hello\",42,true,null]")
        val reifier = parser.parseDepth0<String>()
        val result = reifier.force(JsonFactory)
        assertEquals("[hello,42,true,null]", result)
    }

    // === RegionScanner integration ===

    @Test
    fun testRegionScannerCollectsAllRegions() {
        val input = """{"a":1,"b":"two"}"""
        val scanner = RegionScanner<String>(input)
        val regions = scanner.scan()

        // Should have regions for: the object, number 1, string "two"
        assertTrue(regions.size >= 3)
    }

    @Test
    fun testRegionScannerDecodeAll() {
        val input = """{"x":42}"""
        val scanner = RegionScanner<String>(input)
        scanner.scan()
        val decoded = scanner.decodeAll(JsonFactory)

        // All regions should decode to non-null values
        assertTrue(decoded.isNotEmpty())
        decoded.values.forEach { value ->
            assertTrue(value.isNotEmpty())
        }
    }

    // === Forwarder / Recognizer pattern ===

    @Test
    fun testForwarderRecognizePattern() {
        // Test that the Forwarder -> Recognizer -> Reifier chain works
        val forwarder: Forwarder<String> = { "test-value" }
        val recognizer: Recognizer<String> = recognize(forwarder)

        // The recognizer strips KClass and forwards Region to Forwarder
        val result = recognizer(String::class, Region.of(0, 10))
        assertEquals("test-value", result)
    }

    @Test
    fun testForwarderCapturesValue() {
        var callCount = 0
        val forwarder: Forwarder<String> = { callCount++; "captured" }
        val recognizer: Recognizer<String> = recognize(forwarder)

        // Calling recognizer should invoke the forwarder
        recognizer(String::class, Region.of(0, 1))
        assertEquals(1, callCount)

        recognizer(String::class, Region.of(2, 3))
        assertEquals(2, callCount)
    }
}

