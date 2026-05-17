package borg.trikeshed.parse.confix

import borg.trikeshed.parse.confix.Path
import borg.trikeshed.parse.confix.Combinators
import borg.trikeshed.parse.confix.Syntax
import borg.trikeshed.lib.asSeries
import borg.trikeshed.parse.confix.contextOf
import borg.trikeshed.parse.confix.path
import kotlin.test.Test
import kotlin.test.assertFails
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class ConfixYamlTest {
    @Test
    fun yamlQuotedStringUnescape() {
        // YAML inline double-quoted scalar with an escaped newline sequence
        val yaml = """list:
  - 'one'
  - "two\nline"
"""
        val ctx = contextOf(Syntax.YAML, yaml.asSeries())
        val res = Path.resolve(ctx, path("list", 1))
        assertNotNull(res)
        val v = Combinators.reify(res)
        // Expecting decoded newline
        assertEquals("two\nline", v)
    }

    @Test
    fun yamlMapSequence() {
        val yaml = """map:
  nested:
    - a
    - b
"""
        val ctx = contextOf(Syntax.YAML, yaml.asSeries())
        val res = Path.resolve(ctx, path("map", "nested", 1))
        assertNotNull(res)
        val v = Combinators.reify(res)
        assertEquals("b", v)
    }
}
