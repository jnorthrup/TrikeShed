package borg.trikeshed.graal.subvm

import borg.trikeshed.vm.Teleported
import org.graalvm.polyglot.Value
import org.graalvm.polyglot.proxy.ProxyArray
import org.graalvm.polyglot.proxy.ProxyObject

/**
 * Graal projections of the common [Teleported] ABI (`borg.trikeshed.vm.Teleported`). The sealed
 * tree, canonical encoding, cid and exact parser are common code; only the polyglot [Value]
 * crossings live here, as extensions, so call sites keep the `Teleported.of(value)` /
 * `t.toGuest()` syntax.
 */

/** Rebuild a guest-visible value: primitives as-is, containers as polyglot proxies (allowed under HostAccess.NONE). */
fun Teleported.toGuest(): Any? = when (this) {
    Teleported.Null -> null
    is Teleported.Bool -> v
    is Teleported.Num -> v
    is Teleported.Real -> v
    is Teleported.Str -> v
    is Teleported.Bytes -> ProxyArray.fromArray(*v.map { it.toInt() }.toTypedArray<Any>())
    is Teleported.Arr -> ProxyArray.fromList(v.map { it.toGuest() })
    is Teleported.Obj -> ProxyObject.fromMap(v.mapValues { it.value.toGuest() })
    is Teleported.Opaque -> repr
}

fun Teleported.Companion.of(v: Value?): Teleported = when {
    v == null || v.isNull -> Teleported.Null
    v.isBoolean -> Teleported.Bool(v.asBoolean())
    v.isNumber -> if (v.fitsInLong()) Teleported.Num(v.asLong()) else Teleported.Real(v.asDouble())
    v.isString -> Teleported.Str(v.asString())
    v.hasBufferElements() -> Teleported.Bytes(ByteArray(v.bufferSize.toInt()) { v.readBufferByte(it.toLong()) })
    v.hasArrayElements() -> Teleported.Arr((0 until v.arraySize).map { of(v.getArrayElement(it)) })
    v.canExecute() -> Teleported.Opaque("fn:${v.metaObject?.metaSimpleName ?: "function"}")
    v.hasMembers() && !v.isMetaObject -> Teleported.Obj(v.memberKeys.sorted().associateWith { of(v.getMember(it)) })
    else -> Teleported.Opaque(v.toString())
}

/** Host projection that also accepts polyglot [Value]s (the common `ofHost` cannot see them). */
fun Teleported.Companion.ofGuestOrHost(o: Any?): Teleported = when (o) {
    is Value -> of(o)
    is List<*> -> Teleported.Arr(o.map { ofGuestOrHost(it) })
    is Map<*, *> -> Teleported.Obj(o.entries.associate { it.key.toString() to ofGuestOrHost(it.value) }.toList().sortedBy { it.first }.toMap())
    else -> ofHost(o)
}

fun Teleported.Companion.args(vararg vs: Value): Teleported.Arr = Teleported.Arr(vs.map { of(it) })
