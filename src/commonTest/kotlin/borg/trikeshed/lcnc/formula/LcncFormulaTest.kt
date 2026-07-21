package borg.trikeshed.lcnc.formula

import borg.trikeshed.lcnc.collections.associative.*
import kotlin.test.Test
import kotlin.test.assertEquals

class LcncFormulaTest {
    @Test
    fun testParseAndEvaluateFormula() {
        val parser = FormulaParser("""if(prop("Done"), 1, 0)""")
        val ast = parser.parse()
        
        val row1 = mapOf("Done" to PropertyValue("done_id", PropertyType.CHECKBOX, true))
        val row2 = mapOf("Done" to PropertyValue("done_id", PropertyType.CHECKBOX, false))
        
        val result1 = ast.evaluate(row1)
        val result2 = ast.evaluate(row2)
        
        assertEquals(1.0, result1)
        assertEquals(0.0, result2)
    }

    @Test
    fun testFormulaReducer() {
        val reducer = FormulaReducer("""if(prop("Done"), 1, 0)""")
        val row1 = mapOf("Done" to PropertyValue("done_id", PropertyType.CHECKBOX, true))
        
        val result = reducer.reduce(row1)
        assertEquals(1.0, result)
    }
}
