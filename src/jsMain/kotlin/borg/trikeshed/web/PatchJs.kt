package borg.trikeshed.web

import kotlinx.browser.window
import kotlinx.coroutines.await
import kotlin.js.Promise

/** JS value vocabulary the patch surfaces share: the graph they edit is plain JSON-shaped JS objects. */
fun obj(): dynamic = js("({})")
/** a fresh JS object filled by [init] */
fun objOf(init: (dynamic) -> Unit): dynamic { val o = obj(); init(o); return o }
fun jsArray(): dynamic = js("[]")
fun arr(d: dynamic): Array<dynamic> = if (d == null) emptyArray() else d.unsafeCast<Array<dynamic>>()
fun truthy(v: dynamic): Boolean = js("!!v").unsafeCast<Boolean>()
fun keysOf(o: dynamic): Array<String> = js("Object.keys(o)").unsafeCast<Array<String>>()
fun isArray(v: dynamic): Boolean = js("Array.isArray(v)").unsafeCast<Boolean>()
fun typeOf(v: dynamic): String = js("typeof v").unsafeCast<String>()
fun num(v: dynamic): Double = js("+v").unsafeCast<Double>()
fun str(v: dynamic): String = js("String(v)").unsafeCast<String>()
fun assign(target: dynamic, source: dynamic): dynamic = js("Object.assign(target, source)")
fun spread(source: dynamic): dynamic = js("Object.assign({}, source)")
fun jsNew(ctor: dynamic, a: dynamic = undefined): dynamic = js("new ctor(a)")

external fun encodeURIComponent(s: String): String
external fun decodeURIComponent(s: String): String

/** a thrown JS value that is not an Error (runners refuse with a string or the daemon's error object) */
class PatchRefusal(val value: dynamic) : Throwable(str(value))

/** `e&&e.message||e` over a Kotlin or JS failure */
fun failureText(e: dynamic): String {
    val v: dynamic = if (e is PatchRefusal) e.value else e
    return str(if (truthy(v) && truthy(v.message)) v.message else v)
}

fun on(target: dynamic, type: String, handler: (dynamic) -> Unit, options: dynamic = undefined) {
    target.addEventListener(type, handler, options)
}

fun off(target: dynamic, type: String, handler: (dynamic) -> Unit, options: dynamic = undefined) {
    target.removeEventListener(type, handler, options)
}

suspend fun awaitJs(p: dynamic): dynamic = p.unsafeCast<Promise<dynamic>>().await()

suspend fun fetchJs(url: String, init: dynamic = undefined): dynamic = awaitJs(window.asDynamic().fetch(url, init))

/** fetch then parse the body as JSON; the response is bound before the dynamic call (a suspend result must not be chained through `dynamic`) */
suspend fun fetchJson(url: String, init: dynamic = undefined): dynamic { val r = fetchJs(url, init); return awaitJs(r.json()) }

fun jsonInit(method: String, body: dynamic, type: String = "application/json"): dynamic {
    val init = obj(); init.method = method
    val headers = obj(); headers["Content-Type"] = type; init.headers = headers
    init.body = body
    return init
}

/** JSON in, JSON out; a non-JSON reply arrives as {raw,status} */
suspend fun api(method: String, path: String, body: dynamic = undefined): dynamic {
    val r = fetchJs(path, jsonInit(method, if (body !== undefined && method != "GET") JSON.stringify(body) else undefined))
    val t: String = awaitJs(r.text())
    return try { JSON.parse(t) } catch (e: dynamic) { val o = obj(); o.raw = t; o.status = r.status; o }
}

/** Bounded response body reads (landscape.js readBytes/readText). */
object PatchRead {
    suspend fun bytes(response: dynamic, limit: Int = 1048576): dynamic {
        val reader = response.body?.getReader() ?: throw Error("Response stream unavailable")
        val chunks = ArrayList<dynamic>()
        var size = 0
        try {
            if (num(response.headers.get("content-length")) > limit) throw Error("payload_limit")
            while (true) {
                val step = awaitJs(reader.read())
                if (truthy(step.done)) break
                val value = step.value
                size += (value.byteLength as Int)
                if (size > limit) throw Error("payload_limit")
                chunks.add(value)
            }
        } catch (e: dynamic) {
            try { awaitJs(reader.cancel()) } catch (_: dynamic) {}
            throw e
        } finally { reader.releaseLock() }
        val bytes: dynamic = js("new Uint8Array(size)")
        var offset = 0
        for (chunk in chunks) { bytes.set(chunk, offset); offset += (chunk.byteLength as Int) }
        return bytes
    }

    suspend fun text(response: dynamic, limit: Int = 1048576): String {
        val data = bytes(response, limit)
        return js("new TextDecoder().decode(data)").unsafeCast<String>()
    }
}
