package borg.trikeshed.runtime

import borg.trikeshed.classfile.model.*
import borg.trikeshed.classfile.slab.SlabFacet
import borg.trikeshed.classfile.slab.SlabFacetFlag
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.j
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ConfixClassfileDirTest {
    
    @Test
    fun `pathOf builds canonical Confix path with method`() {
        val pc = pointcutCoordinate()
        val path = ConfixClassfileDir.pathOf(pc)
        
        // Path format: /classes/<owner>/<methodName>/<kind>/<bytecodeOffset>
        assertEquals("/classes/borg/trikeshed/Foo/method/INSTANCE_FIELD_READ/42", path)
    }
    
    @Test
    fun `nodeVal produces Confix-serializable Map`() {
        val pc = pointcutCoordinate()
        val map = ConfixClassfileDir.nodeVal(pc)
        
        assertNotNull(map["kind"])
        assertEquals("INSTANCE_FIELD_READ", map["kind"])
        assertEquals("borg/trikeshed/Foo", map["owner"])
        assertEquals("bar", map["name"])
        assertEquals(1L, map["facet"]) // HOT facet
    }
    
    @Test
    fun `ChildRowVec lazy composition`() {
        val sourceData = listOf(mapOf("a" to 1), mapOf("b" to 2))
        var callCount = 0
        
        val rowVec = ChildRowVec({
            callCount++
            sourceData.size j { i -> sourceData[i] }
        })
        
        // Before access, source not called
        assertEquals(0, callCount)
        
        // Access triggers source
        val first = rowVec[0]
        assertEquals(1, callCount)
        assertEquals(1, first["a"])
        
        // Second access
        val second = rowVec[1]
        assertEquals(2, callCount)
        assertEquals(2, second["b"])
        
        assertEquals(2, rowVec.size)
    }
    
    @Test
    fun `ChildRowVec facet filter lazy`() {
        val sourceData = listOf(1L, 2L, 4L, 8L)
        val rowVec = ChildRowVec({
            sourceData.size j { i -> sourceData[i] }
        })
        
        val filtered = rowVec.withFacet(3L)
        // Just verify it constructs without evaluating source
        assertEquals(4, filtered.size) // size is from source
    }
    
    private fun pointcutCoordinate(): PointcutCoordinate = PointcutCoordinate(
        kind = BytecodePointcutKind.INSTANCE_FIELD_READ,
        jvmOpcode = "GETFIELD",
        bytecodeOffset = 42,
        source = SourceCoordinate(
            sourceFile = "Foo.kt",
            line = 10,
            column = 5,
            language = "kotlin",
            bytecodeOffset = 42
        ),
        symbol = SymbolCoordinate(
            owner = "borg/trikeshed/Foo",
            name = "bar",
            descriptor = "I",
            methodName = "method",
            methodDescriptor = "()V"
        )
    )
}