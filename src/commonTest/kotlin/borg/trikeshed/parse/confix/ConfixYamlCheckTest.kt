package borg.trikeshed.parse.confix

import borg.trikeshed.collections.text.asSeries
import kotlin.test.Test

class ConfixYamlCheckTest {
    @Test
    fun checkQuotedAndMapSequence() {
        val yaml1 = """list:
  - 'one'
  - "two\nline"
"""
        val ctx1 = contextOf(Syntax.YAML, yaml1.asSeries())
        val res1 = Path.resolve(ctx1, path("list", 1))
        println("Case1 resolved -> $res1")
        if (res1 != null) println("Case1 reified -> ${Combinators.reify(res1)}")

        val yaml2 = """map:
  nested:
    - a
    - b
"""
        val ctx2 = contextOf(Syntax.YAML, yaml2.asSeries())
        val res2 = Path.resolve(ctx2, path("map", "nested", 1))
        println("Case2 resolved -> $res2")
        if (res2 != null) println("Case2 reified -> ${Combinators.reify(res2)}")
    }
}
