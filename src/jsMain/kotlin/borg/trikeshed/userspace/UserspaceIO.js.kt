package borg.trikeshed.userspace

import borg.trikeshed.userspace.nio.ByteBuffer
import kotlin.js.JsName
import kotlin.js.Promise
import kotlinx.coroutines.await
import org.khronos.webgl.ArrayBuffer
import org.khronos.webgl.Int8Array

private object JsFileRegistry {
    private var nextId = 1
    private val contentCache = mutableMapOf<String, ByteArray>()

    fun open(path: String, readOnly: Boolean): FileImpl {

@PublishedApi
internal suspend fun loadFile(path: String) {
    // Optimized: Replace Promise .then() chain with consecutive .await() calls
    // to ensure we yield to the JS event loop properly through Kotlin's coroutine dispatcher,
    // preventing blocking microtask accumulation.
    val resp = jsFetch(path).await()
    val buf = resp.arrayBuffer().await()
    val int8Array = Int8Array(buf.unsafeCast<ArrayBuffer>())
    val arr = int8Array.unsafeCast<ByteArray>()
    JsFileRegistry.cache(path, arr)
}

@JsName("fetch")
external fun jsFetch(input: String): Promise<JsResponse>

@JsName("Response")
external class JsResponse {
    val ok: Boolean
    val status: Int
    fun arrayBuffer(): Promise<dynamic>
    fun text(): Promise<String>
}