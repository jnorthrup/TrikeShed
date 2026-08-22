package borg.trikeshed.userspace.containment

import kotlin.test.Test
import kotlin.test.assertEquals

class DeterministicFormatterTest {

    @Test
    fun formats_basic_whitespace() {
        val input = "fun main() {\n\tprintln(\"hello\")  \n\n\n}\n"
        val expected = "fun main() {\n    println(\"hello\")\n\n}\n"
        assertEquals(expected, DeterministicFormatter.format(input))
    }

    @Test
    fun sorts_imports() {
        val input = """
            import java.util.List
            import java.util.ArrayList
            
            class Foo {}
        """.trimIndent()
        
        val expected = """
            import java.util.ArrayList
            import java.util.List
            
            class Foo {}
            
        """.trimIndent()
        
        assertEquals(expected, DeterministicFormatter.format(input))
    }
}
