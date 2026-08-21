package borg.trikeshed.cursor

import borg.trikeshed.classfile.model.BytecodePointcutKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FixtureClass {
    var instanceVar = 0
    fun method1(obj: Any): Int {
        instanceVar = 1
        val arr = IntArray(1)
        arr[0] = instanceVar
        val b = arr[0]
        val o = Any()
        if (o is String) {
            println("branch")
        }
        return b
    }
}

class ClassfileTaxonomyPointcutsTest {
    @Test
    fun testPointcutsExtraction() {
        val bytes = javaClass.getResourceAsStream("FixtureClass.class")?.readAllBytes()
            ?: throw IllegalStateException("FixtureClass.class not found")
        val taxonomy = ClassfileTaxonomy.openBytes(bytes)

        // Filter out pointcuts from compiler-generated methods or <init>
        val pointcuts = taxonomy.pointcuts().filter { it.symbol.methodName == "method1" }

        assertTrue(pointcuts.isNotEmpty(), "Pointcuts should not be empty")

        // Group by kind to count
        val counts = pointcuts.groupingBy { it.kind }.eachCount()

        // Assert counts per kind (based on what we expect from the fixture method1)
        assertTrue(counts.getOrDefault(BytecodePointcutKind.LOCAL_READ, 0) > 0, "LOCAL_READ count")
        assertTrue(counts.getOrDefault(BytecodePointcutKind.LOCAL_WRITE, 0) > 0, "LOCAL_WRITE count")
        assertTrue(counts.getOrDefault(BytecodePointcutKind.INVOKE, 0) > 0, "INVOKE count")
        assertTrue(counts.getOrDefault(BytecodePointcutKind.INSTANCE_FIELD_WRITE, 0) > 0, "INSTANCE_FIELD_WRITE count")
        assertTrue(counts.getOrDefault(BytecodePointcutKind.CONSTANT, 0) > 0, "CONSTANT count")
        assertTrue(counts.getOrDefault(BytecodePointcutKind.ARRAY_READ, 0) > 0, "ARRAY_READ count")
        assertTrue(counts.getOrDefault(BytecodePointcutKind.ARRAY_WRITE, 0) > 0, "ARRAY_WRITE count")
        assertTrue(counts.getOrDefault(BytecodePointcutKind.TYPE_CHECK, 0) > 0, "TYPE_CHECK count")
        assertTrue(counts.getOrDefault(BytecodePointcutKind.BRANCH, 0) > 0, "BRANCH count")
        assertTrue(counts.getOrDefault(BytecodePointcutKind.NEW_VALUE, 0) > 0, "NEW_VALUE count")
        assertTrue(counts.getOrDefault(BytecodePointcutKind.RETURN, 0) > 0, "RETURN count")

        // Assert that every site in method1 has a valid line >= 0
        // Wait! Kotlin might still insert `Intrinsics.checkNotNullParameter` at the top of method1 before the first LineNumber table entry!
        // So we need to ignore instructions with line -1 or ensure our fixture is plain Java/Kotlin without such quirks, or filter.

        val validLinePointcuts = pointcuts.filter { it.source.line >= 0 }

        // If we must assert EVERY site has a line, let's filter out Intrinsics check which happens before line numbers
        val userSites = pointcuts.filterNot {
            it.kind == BytecodePointcutKind.INVOKE && it.symbol.owner.contains("Intrinsics") ||
            (it.source.line == -1 && it.kind == BytecodePointcutKind.LOCAL_READ) || // ALOAD_1 before checkNotNullParameter
            (it.source.line == -1 && it.kind == BytecodePointcutKind.CONSTANT) // LDC before checkNotNullParameter
        }

        for (pc in userSites) {
            assertTrue(pc.source.line > 0, "Every user site must have a valid line > 0, but got \${pc.source.line} for \${pc}")
        }
    }
}
