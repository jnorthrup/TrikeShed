package borg.trikeshed.graal.subvm

import borg.trikeshed.vm.Teleported
import org.graalvm.polyglot.Value
import org.graalvm.polyglot.proxy.ProxyArray
import org.graalvm.polyglot.proxy.ProxyObject
import java.math.BigDecimal
import java.math.BigInteger

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
    // JVM languages can return decimals that Graal does not expose as guest numbers.
    // Admit only a lossless decimal round trip to the existing numeric ABI.
    v.isHostObject && v.asHostObject<Any>() is BigDecimal -> {
        val decimal = v.asHostObject<BigDecimal>()
        runCatching { Teleported.Num(decimal.longValueExact()) }.getOrElse {
            val number = decimal.toDouble()
            if (number.isFinite() && BigDecimal.valueOf(number).compareTo(decimal) == 0)
                Teleported.Real(number)
            else Teleported.Opaque("number:$decimal")
        }
    }
    v.isHostObject && v.asHostObject<Any>() is BigInteger -> {
        val integer = v.asHostObject<BigInteger>()
        if (integer.bitLength() <= 63) Teleported.Num(integer.toLong())
        else Teleported.Opaque("number:$integer")
    }
    v.isNumber -> if (v.fitsInLong()) Teleported.Num(v.asLong()) else Teleported.Real(v.asDouble())
    // TruffleRuby Symbols also answer isString, but are not text: :x and "x"
    // can coexist as Hash keys. Collapsing them would silently discard data.
    v.isString -> if (v.metaObject?.metaSimpleName == "Symbol") Teleported.Opaque("symbol:${v.asString()}")
        else Teleported.Str(v.asString())
    depth >= TELEPORT_MAX_DEPTH -> Teleported.Opaque(v.toString())
    v.hasBufferElements() -> Teleported.Bytes(ByteArray(v.bufferSize.toInt()) { v.readBufferByte(it.toLong()) })
    // Hash protocol BEFORE array: GraalPy mappings (dict, os.environ's _Environ) answer
    // hasArrayElements() true via __getitem__ yet throw on getArrayElement(long) — the
    // string-keyed lookup is the real shape.
    v.hasHashEntries() -> runCatching {
        require(v.hashSize <= TELEPORT_MAX_ELEMENTS) { "Hash exceeds teleport bounds" }
        Teleported.Obj(
            buildList {
                // TruffleRuby's hash iterator is a fiber, forbidden by the guest's
                // no-thread policy. Hash#to_a yields the same pairs without a fiber.
                val ruby = v.metaObject?.metaQualifiedName == "Hash" && v.canInvokeMember("to_a")
                val entries = if (ruby) v.invokeMember("to_a") else v.hashEntriesIterator
                if (ruby) require(entries.hasArrayElements() && entries.arraySize == v.hashSize)
                var index = 0L
                while (if (ruby) index < entries.arraySize else entries.hasIteratorNextElement()) {
                    require(size < TELEPORT_MAX_ELEMENTS) { "Hash exceeds teleport bounds" }
                    val e = if (ruby) entries.getArrayElement(index++) else entries.iteratorNextElement
                    val k = teleportOf(e.getArrayElement(0), depth + 1)
                    require(k is Teleported.Str) { "Hash keys must be text to teleport without loss" }
                    add(k.v to teleportOf(e.getArrayElement(1), depth + 1))
                }
            }.sortedBy { it.first }.toMap(),
        )
    }.getOrElse { Teleported.Opaque(v.toString()) }
    v.hasArrayElements() -> runCatching {
        require(v.arraySize <= TELEPORT_MAX_ELEMENTS) { "Array exceeds teleport bounds" }
        Teleported.Arr((0 until v.arraySize).map { teleportOf(v.getArrayElement(it), depth + 1) })
    }.getOrElse { Teleported.Opaque(v.toString()) }
    v.canExecute() -> Teleported.Opaque("fn:${v.metaObject?.metaSimpleName ?: "function"}")
    // Host containers above have an explicit data protocol. Arbitrary JVM objects
    // do not become guest records merely because they expose zero public members.
    v.isHostObject -> Teleported.Opaque("host:${v.asHostObject<Any>().javaClass.name}")
    v.hasMembers() && !v.isMetaObject -> runCatching {
        require(v.memberKeys.size <= TELEPORT_MAX_ELEMENTS) { "Object exceeds teleport bounds" }
        Teleported.Obj(v.memberKeys.sorted().associateWith { teleportOf(v.getMember(it), depth + 1) })
    }.getOrElse { Teleported.Opaque(v.toString()) }
    else -> Teleported.Opaque(v.toString())
}

/** Host projection that also accepts polyglot [Value]s (the common `ofHost` cannot see them). */
fun Teleported.Companion.ofGuestOrHost(o: Any?): Teleported = when (o) {
    is Value -> of(o)
    is List<*> -> Teleported.Arr(o.map { ofGuestOrHost(it) })
    is Map<*, *> -> Teleported.Obj(o.entries.map { it.key.toString() to ofGuestOrHost(it.value) }.sortedBy { it.first }.toMap())
    else -> ofHost(o)
}

fun Teleported.Companion.args(vararg vs: Value): Teleported.Arr = Teleported.Arr(vs.map { of(it) })
