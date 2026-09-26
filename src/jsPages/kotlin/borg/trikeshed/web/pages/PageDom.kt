package borg.trikeshed.web.pages

import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.await
import kotlinx.coroutines.launch
import org.w3c.dom.HTMLElement
import org.w3c.fetch.RequestInit
import org.w3c.fetch.Response
import kotlin.js.Promise

/** The one coroutine scope the page modules launch their fetch sequences on. */
internal val pageScope = MainScope()

internal fun launchPage(block: suspend () -> Unit) {
    pageScope.launch { block() }
}

internal fun byId(id: String): HTMLElement = document.getElementById(id) as HTMLElement

internal fun byIdOrNull(id: String): HTMLElement? = document.getElementById(id) as HTMLElement?

internal fun obj(): dynamic = js("({})")

internal fun jsonHeaders(): dynamic {
    val h: dynamic = obj()
    h["Content-Type"] = "application/json"
    return h
}

internal fun requestInit(method: String? = null, headers: dynamic = undefined, body: Any? = undefined, cache: String? = null, signal: dynamic = undefined): RequestInit {
    val init: dynamic = obj()
    if (method != null) init.method = method
    if (headers != undefined) init.headers = headers
    if (body != undefined) init.body = body
    if (cache != null) init.cache = cache
    if (signal != undefined) init.signal = signal
    return init.unsafeCast<RequestInit>()
}

internal suspend fun fetchResponse(url: String, init: RequestInit? = null): Response =
    (if (init == null) window.fetch(url) else window.fetch(url, init)).await()

internal suspend fun Response.jsonValue(): dynamic = json().await()

/** JS truthiness of a dynamic value. */
internal fun truthy(v: dynamic): Boolean = js("Boolean")(v) as Boolean

internal fun <T> Promise<T>.ignore() {
    then({}, {})
}

internal fun isNumber(v: dynamic): Boolean = jsTypeOf(v) == "number"

internal fun isString(v: dynamic): Boolean = jsTypeOf(v) == "string"

internal fun jsArray(v: dynamic): Array<dynamic> = if (js("Array").isArray(v) as Boolean) v.unsafeCast<Array<dynamic>>() else emptyArray()

internal fun str(v: dynamic): String = js("String")(v) as String

/** `x ?? ""` then String(). */
internal fun strOrEmpty(v: dynamic): String = if (v == null) "" else str(v)

internal fun setTimeout(ms: Int, f: () -> Unit): Int = window.setTimeout({ f() }, ms)
