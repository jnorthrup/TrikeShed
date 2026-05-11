package borg.trikeshed.couch.requestfactory

sealed interface TransportValue {
    object NullValue : TransportValue

    data class StringValue(val value: String) : TransportValue
    data class BooleanValue(val value: Boolean) : TransportValue
    data class IntegerValue(val value: Long) : TransportValue
    data class NumberValue(val value: Double) : TransportValue
    data class ArrayValue(val values: List<TransportValue>) : TransportValue
    data class ObjectValue(val values: Map<String, TransportValue>) : TransportValue
}

/** Unwrap to a plain Kotlin value suitable for passing to [CouchViewInvocation.invoke]. */
fun TransportValue.unwrap(): Any? = when (this) {
    is TransportValue.NullValue -> null
    is TransportValue.StringValue -> value
    is TransportValue.BooleanValue -> value
    is TransportValue.IntegerValue -> value
    is TransportValue.NumberValue -> value
    is TransportValue.ArrayValue -> values.map { it.unwrap() }
    is TransportValue.ObjectValue -> values.mapValues { it.value.unwrap() }
}
