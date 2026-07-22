package borg.trikeshed.serialization

import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.ExperimentalSerializationApi

object PortableCbor {
    @OptIn(ExperimentalSerializationApi::class)
    val cbor = Cbor {
        ignoreUnknownKeys = true
        encodeDefaults = true // Or whatever is needed
        // How to configure definite-length maps? Cbor parser properties: 
        // Unfortunately kotlinx.serialization.cbor doesn't have an option for this?
        // Let's check.
    }

    fun <T> encodeToByteArray(serializer: SerializationStrategy<T>, value: T): ByteArray {
        return cbor.encodeToByteArray(serializer, value)
    }

    fun <T> decodeFromByteArray(deserializer: DeserializationStrategy<T>, bytes: ByteArray): T {
        return cbor.decodeFromByteArray(deserializer, bytes)
    }
}
