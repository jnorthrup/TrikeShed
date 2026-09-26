package borg.trikeshed.web.graal

import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.await
import org.khronos.webgl.ArrayBuffer
import org.w3c.dom.HTMLElement
import org.w3c.fetch.RequestInit
import org.w3c.fetch.Response
import kotlin.js.Promise

/** JS value semantics the Graal pages rely on: truthiness, `||`, String(), toLocaleString, toFixed. */
fun truthy(a: dynamic): Boolean = js("!!a")
fun orr(a: dynamic, b: dynamic): dynamic = if (truthy(a)) a else b
fun str(a: dynamic): String = js("String(a)")
/** `(x ?? '').toString()` */
fun s0(a: dynamic): String = if (a == null) "" else str(a)
fun num(a: dynamic): Double = js("Number(a)")
fun loc(a: dynamic): String = js("(a).toLocaleString()")
fun toFixed(a: dynamic, d: Int): String = js("Number(a).toFixed(d)")
fun jsRound(a: Double): Double = js("Math.round(a)")
fun typeOf(a: dynamic): String = js("typeof a")
fun isArray(a: dynamic): Boolean = js("Array.isArray(a)")
fun isFiniteNum(a: dynamic): Boolean = js("Number.isFinite(a)")
fun newObject(): dynamic = js("({})")
fun entries(o: dynamic): Array<Array<dynamic>> = js("Object.entries(o)")
fun objectKeys(o: dynamic): Array<String> = js("Object.keys(o)")
fun jsonStringify(x: dynamic): String = js("JSON.stringify(x)")
fun jsonIndent(x: dynamic, n: Int): String = js("JSON.stringify(x,null,n)")
fun encUri(s: String): String = js("encodeURIComponent(s)")
fun decUri(s: String): String = js("decodeURIComponent(s)")
fun timeHms(ms: dynamic): String = js("new Date(ms).toTimeString().slice(0,8)")
fun nowHms(): String = js("new Date().toTimeString().slice(0,8)")
fun perfNow(): Double = window.performance.now()
fun hasCompressionStream(): Boolean = js("typeof CompressionStream!=='undefined'")

/** Byte length of [buf] after gzip through CompressionStream. */
fun gzipBytes(buf: dynamic): Promise<ArrayBuffer> =
    js("new Response(new Blob([buf]).stream().pipeThrough(new CompressionStream('gzip'))).arrayBuffer()")

fun byId(id: String): HTMLElement? = document.getElementById(id) as HTMLElement?

fun el(tag: String, cls: String? = null, text: String? = null): HTMLElement {
    val n = document.createElement(tag) as HTMLElement
    if (cls != null) n.className = cls
    if (text != null) n.textContent = text
    return n
}

suspend fun fetchResponse(url: String, init: RequestInit? = null): Response =
    (if (init == null) window.fetch(url) else window.fetch(url, init)).await()

suspend fun fetchJson(url: String, init: RequestInit? = null): dynamic = fetchResponse(url, init).json().await()

fun jsonPost(body: String?): RequestInit {
    val init: dynamic = newObject()
    init.method = "POST"
    val headers: dynamic = newObject()
    headers["Content-Type"] = "application/json"
    init.headers = headers
    if (body != null) init.body = body
    return init.unsafeCast<RequestInit>()
}

fun methodInit(method: String): RequestInit {
    val init: dynamic = newObject()
    init.method = method
    return init.unsafeCast<RequestInit>()
}
