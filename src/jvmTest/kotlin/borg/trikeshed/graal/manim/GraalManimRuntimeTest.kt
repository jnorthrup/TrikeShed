package borg.trikeshed.graal.manim

import borg.trikeshed.graal.ConfixBlackboard
import kotlin.io.path.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GraalManimRuntimeTest {

    @Test
    fun `execute simple manim script Circle().shift(RIGHT) produces MP4`() {
        val runtime = GraalManimRuntime()
        val script = "from manim import *\nclass TestScene(Scene):\n    def construct(self):\n        self.play(Circle().shift(RIGHT))"
        
        val result = runtime.execute(script)
        
        assertNotNull(result.outputPath)
        assertTrue(result.outputPath.toString().endsWith(".mp4"), "Output should be MP4 file")
        assertTrue(result.outputPath.toFile().exists(), "MP4 file should exist at ${result.outputPath}")
    }

    @Test
    fun `pointcut activations captured for vector math and method calls`() {
        val runtime = GraalManimRuntime()
        val script = "from manim import *\nclass TestScene(Scene):\n    def construct(self):\n        self.play(Circle().shift(RIGHT))"
        
        val result = runtime.execute(script)
        
        assertNotNull(result.pointcutActivations)
        assertTrue(result.pointcutActivations.size > 0, "Should capture pointcut activations")
        // Check for OPERATOR (vector math) and INVOKE (method calls)
        val kinds = result.pointcutActivations.map { it.kind }.toSet()
        assertTrue(kinds.contains(BytecodePointcutKind.OPERATOR), "Should capture OPERATOR pointcuts for vector math")
        assertTrue(kinds.contains(BytecodePointcutKind.INVOKE), "Should capture INVOKE pointcuts for method calls")
    }

    @Test
    fun `confix blackboard receives entries during execution`() {
        val runtime = GraalManimRuntime()
        val script = "from manim import *\nclass TestScene(Scene):\n    def construct(self):\n        self.play(Circle().shift(RIGHT))"
        
        val result = runtime.execute(script)
        
        assertNotNull(result.confixBlackboardEntries)
        assertTrue(result.confixBlackboardEntries.isNotEmpty(), "Blackboard should receive entries")
        val entry = result.confixBlackboardEntries.first()
        assertNotNull(entry.key)
        assertNotNull(entry.value)
        assertNotNull(entry.provenance)
    }

    @Test
    fun `duration measured in milliseconds`() {
        val runtime = GraalManimRuntime()
        val script = "from manim import *\nclass TestScene(Scene):\n    def construct(self):\n        self.play(Circle().shift(RIGHT))"
        
        val result = runtime.execute(script)
        
        assertTrue(result.durationMs > 0, "Duration should be positive: ${result.durationMs}ms")
    }
}
