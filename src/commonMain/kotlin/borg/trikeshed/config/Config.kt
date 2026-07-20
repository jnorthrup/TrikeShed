package borg.trikeshed.config

enum class ConfigType {
    STRING, INT, BOOLEAN, DOUBLE
}

sealed class ConfigValue {
    abstract val value: Any

    data class StringValue(override val value: String) : ConfigValue()
    data class IntValue(override val value: Int) : ConfigValue()
    data class BooleanValue(override val value: Boolean) : ConfigValue()
    data class DoubleValue(override val value: Double) : ConfigValue()
}

data class ConfigSchema(
    val properties: Map<String, ConfigType>
) {
    fun validate(key: String, value: ConfigValue): Boolean {
        val expectedType = properties[key] ?: return false
        return when (expectedType) {
            ConfigType.STRING -> value is ConfigValue.StringValue
            ConfigType.INT -> value is ConfigValue.IntValue
            ConfigType.BOOLEAN -> value is ConfigValue.BooleanValue
            ConfigType.DOUBLE -> value is ConfigValue.DoubleValue
        }
    }
}
