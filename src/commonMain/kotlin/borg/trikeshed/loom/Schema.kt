package borg.trikeshed.loom

import borg.trikeshed.collections.associative.*
import borg.trikeshed.lib.*

/** cocaine-rats crates/loom-mesh/src/schema.rs: positional wire schemas over [decode]d items. */
fun array(item: Item, length: Int): Series<Item> =
    if (item is Item.Arr && item.size == length) item.items else error("schema array")

fun text(item: Item): String = (item as? Item.Str)?.value ?: error("schema text")

fun bytes(item: Item): ByteArray = (item as? Item.Bin)?.value ?: error("schema bytes")

fun number(item: Item): ULong =
    if (item is Item.Num && item.value >= 0) item.value.toULong() else error("schema number")

fun fixed(item: Item, length: Int): ByteArray =
    bytes(item).also { if (it.size != length) error("schema byte length") }

fun label(label: String) {
    if (label.isEmpty() || label.length > 64 ||
        !label.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' || it == '_' || it == '.' }
    ) error("invalid label")
}

fun tag(item: Item, expected: String) {
    if (text(item) != expected) error("schema version")
}

fun trusted_time(time: ULong) {
    if (time > Long.MAX_VALUE.toULong()) error("clock range")
}

/** The wall clock in Unix seconds, refused before the epoch ("clock") and past [trusted_time]. */
fun now(): ULong {
    val seconds = kotlin.time.Clock.System.now().epochSeconds
    if (seconds < 0) error("clock")
    return seconds.toULong().also(::trusted_time)
}
