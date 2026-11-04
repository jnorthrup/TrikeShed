package borg.trikeshed.lcnc

/** Port lookup is data access; it must not depend on a runtime provider family. */
internal fun lcncInputValue(node: LcncNode, inputs: Map<String, Any?>, key: String): Any? = when {
    inputs.containsKey(key) -> inputs[key]
    inputs.containsKey("$key?") -> inputs["$key?"]
    else -> node.params["in:$key"] ?: node.params[key]
}

internal fun lcncInputText(
    node: LcncNode, inputs: Map<String, Any?>, key: String,
    default: String? = null, allowBlank: Boolean = false,
): String {
    val value = lcncInputValue(node, inputs, key) ?: default
    require(value is String && (allowBlank || value.isNotBlank())) { "$key must be ${if (allowBlank) "" else "nonblank "}text" }
    return value
}
