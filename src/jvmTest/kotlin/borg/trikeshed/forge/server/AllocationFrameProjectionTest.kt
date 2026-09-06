package borg.trikeshed.forge.server

import borg.trikeshed.graal.vitals.AllocationFrame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AllocationFrameProjectionTest {
    @Test
    fun resolvesJrtAndPrimitiveBoxingWithoutPretendingClasspathIsAot() {
        val frame = AllocationFrame("java.lang.Integer", "valueOf", "(I)Ljava/lang/Integer;", 0, -1, "Inlined", "bootstrap", 0)
        val result = AllocationFrameProjection(null).project(frame)
        assertEquals(true, result["available"])
        assertTrue(result["resource"].toString().startsWith("jrt:"))
        assertEquals(true, result["methodFound"])
        assertEquals(true, result["bciMatched"])
        assertTrue((result["instructions"] as List<*>).isNotEmpty())
        assertTrue(result["aotSiteEvidence"].toString().startsWith("unavailable"))
        assertTrue(result["bytecodeEvidence"].toString().contains("unverified"))
    }

    @Test
    fun identifiesBoxingBridgeAndGracefullyHandlesMissingClasses() {
        val frame = AllocationFrame("borg.trikeshed.lib.JoinKt", "get", "(Lborg/trikeshed/lib/Join;Ljava/lang/Object;)Ljava/lang/Object;", -1, -1, "Inlined", "app", 1)
        val result = AllocationFrameProjection(null).project(frame)
        assertEquals(true, result["available"])
        val absent = AllocationFrameProjection(null).project(frame.copy(className = "missing.Example"))
        assertEquals(false, absent["available"])
        assertEquals("class_resource_unavailable", absent["reason"])
        val boxing = AllocationFrameProjection(null).project(frame.copy(className = "kotlin.coroutines.jvm.internal.Boxing", method = "boxInt", descriptor = "(I)Ljava/lang/Integer;"))
        assertTrue((boxing["instructions"] as List<*>).any { (it as Map<*, *>)["boxing"] == true })
    }
}
