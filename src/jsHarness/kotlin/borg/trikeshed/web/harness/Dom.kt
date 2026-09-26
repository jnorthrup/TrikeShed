package borg.trikeshed.web.harness

import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.await
import org.w3c.dom.Element
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.EventTarget
import org.w3c.fetch.RequestInit
import org.w3c.fetch.Response
import kotlin.js.Promise

@JsName("Map")
external class JsMap<K, V> {
    val size: Int
    fun get(key: K): V?
    fun set(key: K, value: V): JsMap<K, V>
    fun has(key: K): Boolean
    fun delete(key: K): Boolean
    fun clear()
}

@JsName("Set")
external class JsSet<T> {
    val size: Int
    fun has(value: T): Boolean
    fun add(value: T): JsSet<T>
}

external class AbortSignal : EventTarget {
    val aborted: Boolean
    fun throwIfAborted()
}

external class AbortController {
    val signal: AbortSignal
    fun abort()
}

/** Snapshot of a JS Map's keys, in insertion order. */
fun <K, V> JsMap<K, V>.keyList(): List<K> = (js("Array").from(this.asDynamic().keys()) as Array<K>).toList()

/** Snapshot of a JS Map's entries, in insertion order. */
fun <K, V> JsMap<K, V>.entryList(): List<Pair<K, V>> =
    (js("Array").from(this) as Array<Array<dynamic>>).map { (it[0] as K) to (it[1] as V) }

fun <K, V> JsMap<K, V>.valueList(): List<V> = entryList().map { it.second }

fun q(selector: String): HTMLElement = document.querySelector(selector).unsafeCast<HTMLElement>()
fun qOrNull(selector: String): HTMLElement? = document.querySelector(selector).unsafeCast<HTMLElement?>()
fun byId(id: String): HTMLElement = document.getElementById(id).unsafeCast<HTMLElement>()

fun el(tag: String, cls: String? = null, value: Any? = null): HTMLElement {
    val e = document.createElement(tag).unsafeCast<HTMLElement>()
    if (!cls.isNullOrEmpty()) e.className = cls
    if (value != null) e.textContent = value.toString()
    return e
}

fun Element.replaceKids(vararg children: dynamic) { this.asDynamic().replaceChildren.apply(this, children) }
fun Element.appendAll(vararg children: dynamic) { this.asDynamic().append.apply(this, children) }

fun jsObject(): dynamic = js("({})")
fun truthy(v: dynamic): Boolean = js("!!v").unsafeCast<Boolean>()
fun hasOwn(target: dynamic, key: String): Boolean = js("Object").hasOwn(target, key) as Boolean
fun keysOf(target: dynamic): List<String> =
    if (target == null) emptyList() else (js("Object").keys(target) as Array<String>).toList()
fun isObject(value: dynamic): Boolean = value != null && jsTypeOf(value) == "object"
fun jsonString(value: dynamic, pretty: Boolean = false): String =
    if (pretty) js("JSON.stringify(value, null, 2)") as String else JSON.stringify(value)
fun jsString(value: dynamic): String = js("String")(value) as String
fun jsNumber(value: dynamic): Double = js("Number")(value) as Double
fun errorMessage(e: Throwable): String = (e.asDynamic().message ?: e.toString()).toString()

fun requestInit(method: String? = null, json: String? = null, signal: AbortSignal? = null): RequestInit {
    val init: dynamic = jsObject()
    if (method != null) init.method = method
    if (json != null) { init.headers = js("({\"Content-Type\":\"application/json\"})"); init.body = json }
    if (signal != null) init.signal = signal
    return init.unsafeCast<RequestInit>()
}

suspend fun fetchResponse(url: String, init: RequestInit? = null): Response =
    (if (init == null) window.fetch(url) else window.fetch(url, init)).await()

suspend fun Response.jsonValue(): dynamic = json().await()

fun <T> Promise<T>.ignoreFailure(): Promise<Any?> = this.then({ it }, { null })

/** A fresh JS object populated by [fill]. */
inline fun obj(fill: (dynamic) -> Unit): dynamic { val o: dynamic = js("({})"); fill(o); return o }
