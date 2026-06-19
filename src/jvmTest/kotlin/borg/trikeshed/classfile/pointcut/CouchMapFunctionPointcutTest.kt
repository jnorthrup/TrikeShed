package borg.trikeshed.classfile.pointcut

import borg.trikeshed.classfile.model.BytecodePointcutKind
import borg.trikeshed.classfile.model.div
import borg.trikeshed.lib.α
import borg.trikeshed.lib.view
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * TDD RED test: CouchDB-style map function execution with pointcut tracking.
 *
 * This test demonstrates the desired integration where libs/classfile's
 * GraalPolyglotPointcutCommand is used to execute CouchDB map/reduce functions
 * with full pointcut tracking (including emit calls).
 *
 * Currently FAILS (RED) because:
 * - GraalPolyglotPointcutCommand only tracks JS operators (/, %) mapped to JVM bytecodes
 * - Function calls like `emit(key, value)` are not tracked as pointcuts
 * - The bridge class (GraalEcmaValueBridge) only has division/remainder operators
 */
class CouchMapFunctionPointcutTest {

    @Test
    fun `couch map function with emit calls produces pointcut activations`() {
        val sink = RecordingPointcutSink()
        
        // CouchDB-style map function with emit calls - use function expression for valid script syntax
        val mapScript = GraalEcmaScript(
            sourceName = "couch_map.js",
            source = """
                (function(doc) {
                    emit(doc._id, doc.value);
                    emit(doc.name, doc.age);
                })
            """.trimIndent()
        )

        val result = GraalPolyglotPointcutCommand().execute(mapScript, sink)
        val activations = result.activations
        val coordinates = result.coordinates

        // The script should evaluate to a function
        assertTrue(result.value != null, "map function should evaluate to a function")

        // Should have pointcut activations for the emit calls (INVOKEVIRTUAL)
        val emitActivations = activations.view.filter { it.coordinate.jvmOpcode == "INVOKEVIRTUAL" }.toList()
        assertTrue(emitActivations.isNotEmpty(), "emit calls should produce INVOKEVIRTUAL pointcut activations")

        // Should have coordinates for the emit function calls (INVOKE kind)
        val emitCoordinates = coordinates / BytecodePointcutKind.INVOKE
        assertTrue(emitCoordinates.a > 0, "emit calls should produce INVOKE kind coordinates")

        // Verify the emit calls are mapped to source coordinates
        emitCoordinates.view.forEach { coord ->
            assertEquals("couch_map.js", coord.source.sourceFile)
            assertEquals(PolyglotLanguage.ECMA.id, coord.source.language)
            assertTrue(coord.source.line > 0, "emit call should have valid source line")
        }
    }

    @Test
    fun `couch reduce function with rereduce produces pointcut activations`() {
        val sink = RecordingPointcutSink()
        
        // CouchDB-style reduce function - use function expression
        val reduceScript = GraalEcmaScript(
            sourceName = "couch_reduce.js",
            source = """
                (function(keys, values, rereduce) {
                    var sum = 0;
                    for (var i = 0; i < values.length; i++) {
                        sum += values[i];
                    }
                    return sum;
                })
            """.trimIndent()
        )

        val result = GraalPolyglotPointcutCommand().execute(reduceScript, sink)
        val activations = result.activations

        // Should evaluate to the function itself
        assertTrue(result.value != null, "reduce function should evaluate to a function")

        // Should have pointcut activations for the loop and arithmetic
        val operatorActivations = activations.view.filter { it.coordinate.kind == BytecodePointcutKind.OPERATOR }.toList()
        assertTrue(operatorActivations.isNotEmpty(), "reduce function should produce OPERATOR pointcut activations")

        // Should have IADD for the += operation
        val iaddActivations = activations.view.filter { it.coordinate.jvmOpcode == "IADD" }.toList()
        assertTrue(iaddActivations.isNotEmpty(), "reduce function += should produce IADD activations")
    }

    @Test
    fun `couch map function with complex logic produces full pointcut trace`() {
        val sink = RecordingPointcutSink()
        
        // More complex CouchDB map function - use function expression
        val mapScript = GraalEcmaScript(
            sourceName = "couch_complex_map.js",
            source = """
                (function(doc) {
                    if (doc.type === 'user' && doc.active) {
                        var fullName = doc.firstName + ' ' + doc.lastName;
                        emit(fullName, { age: doc.age, email: doc.email });
                    }
                })
            """.trimIndent()
        )

        val result = GraalPolyglotPointcutCommand().execute(mapScript, sink)
        val activations = result.activations
        val coordinates = result.coordinates

        // Should have various pointcut kinds (currently INVOKE for emit, OPERATOR for arithmetic)
        val kinds = activations.α { it.coordinate.kind }.view.toSet()
        assertTrue(kinds.contains(BytecodePointcutKind.INVOKE), "Should have INVOKE for emit")
        assertTrue(kinds.contains(BytecodePointcutKind.OPERATOR), "Should have OPERATOR for arithmetic")
        // COMPARISON and BRANCH not yet implemented in bridge class bytecode

        // Verify dual-phase (BEFORE/AFTER) for each coordinate
        val byCoordinate = activations.view.groupBy { it.coordinate }
        byCoordinate.values.forEach { phaseActivations ->
            val phases = phaseActivations.map { it.phase }.toSet()
            assertEquals(setOf(PointcutPhase.BEFORE, PointcutPhase.AFTER), phases)
        }
    }
}
