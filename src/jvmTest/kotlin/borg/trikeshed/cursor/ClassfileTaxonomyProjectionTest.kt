package borg.trikeshed.cursor

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private interface ClassfileProjectionPrimary {
    fun ping(): String
}

private interface ClassfileProjectionSecondary

private class ClassfileProjectionSubject : ClassfileProjectionPrimary, ClassfileProjectionSecondary {
    override fun ping(): String = "pong"
    fun overload(value: Int): Int = value + 1
    fun overload(value: String): String = value.uppercase()
    fun touches(values: MutableList<String>): String {
        values.add(ping())
        return values[0]
    }
}

class ClassfileTaxonomyProjectionTest {
    @Test
    fun projectionKeepsInterfacesDescriptorsDebugAndPointcuts() {
        val resource = ClassfileProjectionSubject::class.java.name.replace('.', '/') + ".class"
        val bytes = javaClass.classLoader.getResourceAsStream(resource)?.use { it.readBytes() }
        assertNotNull(bytes, "compiled test fixture class missing: $resource")

        val projection = ClassfileTaxonomy.openBytes(bytes).projection()

        @Suppress("UNCHECKED_CAST")
        val interfaces = projection["interfaces"] as List<String>
        assertTrue(interfaces.contains("borg.trikeshed.cursor.ClassfileProjectionPrimary"))
        assertTrue(interfaces.contains("borg.trikeshed.cursor.ClassfileProjectionSecondary"))

        @Suppress("UNCHECKED_CAST")
        val classFile = projection["classFile"] as Map<String, Any?>
        assertEquals(2, classFile["interfaceCount"])
        assertTrue((classFile["methodCount"] as Int) > 0)
        assertTrue((classFile["instructionCount"] as Int) > 0)
        @Suppress("UNCHECKED_CAST")
        val debug = classFile["debug"] as Map<String, Any?>
        assertEquals(true, debug["hasSourceFile"])

        @Suppress("UNCHECKED_CAST")
        val methods = projection["methods"] as List<Map<String, Any?>>
        val overloadDescriptors = methods
            .filter { it["name"] == "overload" }
            .map { it["descriptor"] }
            .toSet()
        assertTrue(overloadDescriptors.contains("(I)I"))
        assertTrue(overloadDescriptors.contains("(Ljava/lang/String;)Ljava/lang/String;"))

        @Suppress("UNCHECKED_CAST")
        val touches = methods.single { it["name"] == "touches" }
        @Suppress("UNCHECKED_CAST")
        val instructions = touches["instructions"] as List<Map<String, Any?>>
        assertTrue(instructions.isNotEmpty())
        assertTrue(instructions.any { (it["sourceLine"] as Int) > 0 })
        assertTrue(instructions.any { it["pointcutKind"] != null })

        @Suppress("UNCHECKED_CAST")
        val pointcuts = projection["pointcuts"] as List<Map<String, Any?>>
        assertTrue(pointcuts.any { pointcut ->
            @Suppress("UNCHECKED_CAST")
            val symbol = pointcut["symbol"] as Map<String, Any?>
            symbol["methodName"] == "touches" && symbol["methodDescriptor"] == "(Ljava/util/List;)Ljava/lang/String;"
        })
    }
}
