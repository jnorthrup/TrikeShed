package borg.trikeshed.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class ConfigTest {
    @Test
    fun testConfigSchemaAndValue() {
        val schema = ConfigSchema(mapOf("port" to ConfigType.INT, "host" to ConfigType.STRING))
        val portValue = ConfigValue.IntValue(8080)
        assertEquals(8080, portValue.value)

        assertTrue(schema.validate("port", portValue))
        assertTrue(schema.validate("host", ConfigValue.StringValue("localhost")))

        assertFalse(schema.validate("port", ConfigValue.StringValue("8080")))
        assertFalse(schema.validate("unknown", ConfigValue.IntValue(1)))
    }
}
