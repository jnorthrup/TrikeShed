package borg.trikeshed.runtime

import borg.trikeshed.classfile.model.*
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