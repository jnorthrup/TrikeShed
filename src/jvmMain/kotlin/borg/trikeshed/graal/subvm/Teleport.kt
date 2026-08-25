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

fun Teleported.Companion.of(v: Value?): Teleported = teleportOf(v, 0)

/** Guest object graphs are cyclic (Python: os.path.os…) and unbounded; primitives pass at any depth. */
private const val TELEPORT_MAX_DEPTH = 5
private const val TELEPORT_MAX_ELEMENTS = 10_000L

private fun teleportOf(v: Value?, depth: Int): Teleported = when {
    v == null || v.isNull -> Teleported.Null
    v.isBoolean -> Teleported.Bool(v.asBoolean())
    v.isNumber -> if (v.fitsInLong()) Teleported.Num(v.asLong()) else Teleported.Real(v.asDouble())
    v.isString -> Teleported.Str(v.asString())
    depth >= TELEPORT_MAX_DEPTH -> Teleported.Opaque(v.toString())
    v.hasBufferElements() -> Teleported.Bytes(ByteArray(v.bufferSize.toInt()) { v.readBufferByte(it.toLong()) })
    // Hash protocol BEFORE array: GraalPy mappings (dict, os.environ's _Environ) answer
    // hasArrayElements() true via __getitem__ yet throw on getArrayElement(long) — the
    // string-keyed lookup is the real shape.
    v.hasHashEntries() -> runCatching {
        Teleported.Obj(
            buildMap {
                val entries = v.hashEntriesIterator
                while (entries.hasIteratorNextElement() && size < TELEPORT_MAX_ELEMENTS) {
                    val e = entries.iteratorNextElement
                    val k = e.getArrayElement(0)
                    put(if (k.isString) k.asString() else k.toString(), teleportOf(e.getArrayElement(1), depth + 1))
                }
            }.toList().sortedBy { it.first }.toMap(),
        )
    }.getOrElse { Teleported.Opaque(v.toString()) }
    v.hasArrayElements() -> runCatching {
        Teleported.Arr((0 until minOf(v.arraySize, TELEPORT_MAX_ELEMENTS)).map { teleportOf(v.getArrayElement(it), depth + 1) })
    }.getOrElse { Teleported.Opaque(v.toString()) }
    v.canExecute() -> Teleported.Opaque("fn:${v.metaObject?.metaSimpleName ?: "function"}")
    v.hasMembers() && !v.isMetaObject -> runCatching {
        Teleported.Obj(v.memberKeys.sorted().associateWith { teleportOf(v.getMember(it), depth + 1) })
    }.getOrElse { Teleported.Opaque(v.toString()) }
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
