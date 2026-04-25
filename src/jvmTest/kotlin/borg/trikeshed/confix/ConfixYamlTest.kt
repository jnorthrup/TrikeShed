package borg.trikeshed.confix

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class ConfixYamlTest {
    @Test
    fun yamlQuotedStringUnescape_red() {
        // YAML inline double-quoted scalar with an escaped newline sequence
        val yaml = """list:
  - 'one'
  - "two\nline"
"""
        val ctx = contextOf(Syntax.YAML, yaml.asSeries())
        val res = Path.resolve(ctx, path("list", 1))
        assertNotNull(res)
        val v = Reify.reify(res)
        // Expecting decoded newline; current Confix returns raw escape -> RED
        assertEquals("two\nline", v)
    }

    @Test
    fun yamlMapSequence_red() {
        val yaml = """map:
  nested:
    - a
    - b
"""
        val ctx = contextOf(Syntax.YAML, yaml.asSeries())
        val res = Path.resolve(ctx, path("map", "nested", 1))
        assertNotNull(res)
        val v = Reify.reify(res)
        assertEquals("b", v)
    }
}
