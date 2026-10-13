package borg.trikeshed.vm

import kotlin.test.*

class VmLanguageTest {
    @Test fun languageDrawerDefaultsDescribeOnlySupportedLimits() {
        for (language in VmLanguage.entries) {
            val capability = VmLanguageCapability(language, true, true, "")
            val params = capability.params
            val budget = VmBudget(params.getValue("statements").toLong(), params.getValue("wallMillis").toLong(),
                params.getValue("calls").toLong())
            language.validate(VmTrust.OWN, budget)
            assertEquals(params, capability.toMap()["params"])
            assertEquals(language.id, params["language"])
            assertTrue(VmLanguageProgram(language, params.getValue("source")).declaration(budget).isNotBlank())
        }
        assertEquals("0", VmLanguageCapability(VmLanguage.JAVA, true, true, "").params["wallMillis"])
        assertEquals("0", VmLanguageCapability(VmLanguage.KOTLIN_COMMON, true, true, "").params["wallMillis"])
        assertEquals(listOf("OWN"), VmLanguageCapability(VmLanguage.KOTLIN_COMMON, true, true, "").toMap()["trust"])
        assertEquals("0", VmLanguageCapability(VmLanguage.CLOJURE, true, true, "").params["wallMillis"])
        assertEquals("input", VmLanguageCapability(VmLanguage.CLOJURE, true, true, "").sample)
        assertEquals("5000", VmLanguageCapability(VmLanguage.GROOVY, true, true, "").params["wallMillis"])
    }

    @Test fun typedValuesPreserveFractionsUnicodeAndNulls() {
        val input = mapOf("text" to "雪\n'\"", "values" to listOf(1.25, null, true))
        assertEquals(input, vmLanguageValue(input).languageValue())
        assertEquals(Teleported.Num(12), vmLanguageValue(12.0))
        assertEquals(Teleported.Real(1.25), vmLanguageValue(1.25))
        assertEquals(Teleported.Null, VmValueKind.NULL.validate(Teleported.Null))
    }

    @Test fun rejectsOpaqueValuesLossyIntegersAndWrongPortKinds() {
        assertFailsWith<IllegalArgumentException> { vmLanguageValue(Long.MAX_VALUE) }
        assertFailsWith<IllegalArgumentException> { vmLanguageValue(Double.NaN) }
        assertFailsWith<IllegalArgumentException> { vmLanguageValue(Teleported.Opaque("host")) }
        assertFailsWith<IllegalArgumentException> { vmLanguageValue(byteArrayOf(1)) }
        assertFailsWith<IllegalArgumentException> { vmLanguageValue(mapOf(1 to "value")) }
        assertFailsWith<IllegalArgumentException> { VmValueKind.OBJECT.validate(Teleported.Str("{}")) }
        var nested: Any? = null
        repeat(7) { nested = listOf(nested) }
        assertFailsWith<IllegalArgumentException> { vmLanguageValue(nested) }
    }

    @Test fun sourceAdaptersDoNotClaimJavaInteropIsACompiler() {
        assertEquals(VmLanguage.KOTLIN_COMMON, VmLanguage.of("kotlin"))
        assertEquals(borg.trikeshed.pointcut.VmFacet.JVM, VmLanguage.KOTLIN_COMMON.facet)
        assertTrue(VmLanguageProgram(VmLanguage.KOTLIN_COMMON, "return input").declaration().contains("KotlinSource"))
        assertEquals(borg.trikeshed.pointcut.VmFacet.JVM, VmLanguage.JAVA.facet)
        assertTrue(VmLanguageProgram(VmLanguage.JAVA, "return input;").declaration().contains("JavaSource"))
        assertFailsWith<IllegalArgumentException> { VmLanguageProgram(VmLanguage.JAVA, "return input;").declaration(VmBudget(wallMillis = 1)) }
        assertTrue(VmLanguageProgram(VmLanguage.PYTHON, "return input").declaration().contains("    return input"))
    }
}
