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
