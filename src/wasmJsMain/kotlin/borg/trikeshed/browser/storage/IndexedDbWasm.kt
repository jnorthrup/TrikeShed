package borg.trikeshed.browser.storage

import kotlinx.browser.window
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import org.khronos.webgl.Uint8Array
import org.khronos.webgl.Int8Array
import org.w3c.dom.Window
import kotlin.js.Promise

// Wasm lacks asDynamic, we need js("") interop
@JsFun("(dbName) => { return new Promise((resolve, reject) => { const req = window.indexedDB.open(dbName, 1); req.onupgradeneeded = (e) => { const db = req.result; if (!db.objectStoreNames.contains('chunks')) { db.createObjectStore('chunks'); } }; req.onsuccess = () => resolve(req.result); req.onerror = () => reject(new Error('Failed to open')); }); }")
private external fun jsOpenDb(dbName: String): Promise<JsAny>

@JsFun("(db, key) => { return new Promise((resolve, reject) => { const tx = db.transaction(['chunks'], 'readonly'); const store = tx.objectStore('chunks'); const req = store.get(key); req.onsuccess = () => resolve(req.result); req.onerror = () => reject(new Error('Failed to read')); }); }")
private external fun jsReadChunk(db: JsAny, key: String): Promise<JsAny?>

@JsFun("(db, key, buffer) => { return new Promise((resolve, reject) => { const tx = db.transaction(['chunks'], 'readwrite'); const store = tx.objectStore('chunks'); const req = store.put(buffer, key); req.onsuccess = () => resolve(req.result); req.onerror = () => reject(new Error('Failed to write')); }); }")
private external fun jsWriteChunk(db: JsAny, key: String, buffer: JsAny): Promise<JsAny>

@JsFun("(bytes) => { const arr = new Int8Array(bytes.length); for(let i=0; i<bytes.length; i++) { arr[i] = bytes[i]; } return arr.buffer; }")
private external fun kotlinByteArrayToJsArrayBuffer(bytes: ByteArray): JsAny

@JsFun("(buffer) => { const arr = new Int8Array(buffer); const bytes = new Int8Array(arr.length); for(let i=0; i<arr.length; i++) { bytes[i] = arr[i]; } return bytes; }")
private external fun jsArrayBufferToKotlinByteArrayWrapper(buffer: JsAny): ByteArray

class IndexedDbWasm(private val dbName: String = "trikeshed-bb") {

    suspend fun open(): Any = suspendCoroutine { cont ->
        jsOpenDb(dbName).then(
            onFulfilled = { db -> cont.resume(db); JsAny?::class.js.unsafeCast<JsAny>() },
            onRejected = { err -> cont.resumeWithException(RuntimeException(err.toString())); JsAny?::class.js.unsafeCast<JsAny>() }
        )
    }

    suspend fun read(db: Any, key: String): ByteArray? = suspendCoroutine { cont ->
        val jsDb = db.unsafeCast<JsAny>()
        jsReadChunk(jsDb, key).then(
            onFulfilled = { result ->
                if (result == null) {
                    cont.resume(null)
                } else {
                    val bytes = jsArrayBufferToKotlinByteArrayWrapper(result)
                    cont.resume(bytes)
                }
                JsAny?::class.js.unsafeCast<JsAny>()
            },
            onRejected = { err ->
                cont.resumeWithException(RuntimeException(err.toString()))
                JsAny?::class.js.unsafeCast<JsAny>()
            }
        )
    }

    suspend fun write(db: Any, key: String, value: ByteArray): Unit = suspendCoroutine { cont ->
        val jsDb = db.unsafeCast<JsAny>()
        val buffer = kotlinByteArrayToJsArrayBuffer(value)
        jsWriteChunk(jsDb, key, buffer).then(
            onFulfilled = { result ->
                cont.resume(Unit)
                JsAny?::class.js.unsafeCast<JsAny>()
            },
            onRejected = { err ->
                cont.resumeWithException(RuntimeException(err.toString()))
                JsAny?::class.js.unsafeCast<JsAny>()
            }
        )
    }
}
