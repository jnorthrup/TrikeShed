package borg.trikeshed.browser.storage

import kotlinx.browser.window
import kotlinx.coroutines.await
import org.khronos.webgl.Int8Array
import org.khronos.webgl.Uint8Array
import kotlin.js.Promise

class OpfsJs {
    private suspend fun getRoot(): Any {
        val storage = window.navigator.asDynamic().storage
        return storage.getDirectory().unsafeCast<Promise<Any>>().await()
    }

    suspend fun openFileHandle(name: String): Any {
        val root = getRoot()
        val promise = root.asDynamic().getFileHandle(name, js("({create: true})"))
        return promise.unsafeCast<Promise<Any>>().await()
    }

    suspend fun read(handle: Any, offset: Long, length: Int): ByteArray {
        val file = handle.asDynamic().getFile().unsafeCast<Promise<Any>>().await()
        val slice = file.asDynamic().slice(offset.toDouble(), offset.toDouble() + length)
        val arrayBuffer = slice.asDynamic().arrayBuffer().unsafeCast<Promise<Any>>().await()
        val uint8Array = Uint8Array(arrayBuffer.unsafeCast<org.khronos.webgl.ArrayBuffer>())
        val int8Array = Int8Array(uint8Array.buffer, uint8Array.byteOffset, uint8Array.length)
        return int8Array.unsafeCast<ByteArray>()
    }

    suspend fun write(handle: Any, offset: Long, data: ByteArray) {
        val writable = handle.asDynamic().createWritable(js("({keepExistingData: true})")).unsafeCast<Promise<Any>>().await()

        // write( { type: "write", position: offset, data: data } )
        val options = js("{}")
        options.type = "write"
        options.position = offset.toDouble()

        // We need to convert Kotlin ByteArray to JS Uint8Array
        val uint8Array = Uint8Array(data.unsafeCast<Int8Array>().buffer, data.unsafeCast<Int8Array>().byteOffset, data.size)
        options.data = uint8Array

        writable.asDynamic().write(options).unsafeCast<Promise<Any>>().await()
        writable.asDynamic().close().unsafeCast<Promise<Any>>().await()
    }
}
