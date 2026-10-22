package borg.trikeshed.parse.confix

import borg.trikeshed.cursor.*
import borg.trikeshed.lib.*
import borg.trikeshed.cursor.ReifiedSplitSeries2

/**
 * Typed columns over a root array of objects — the [ConfixIndexK.Columns] facet.
 *
 * The column set is the union of member keys in first-occurrence order. A column's type is
 * the join of its members' tags: integers widen to [IOMemento.IoLong], any fraction widens
 * the column to [IOMemento.IoDouble], uniform booleans stay [IOMemento.IoBoolean], uniform
 * containers stay [IOMemento.IoObject] / [IOMemento.IoArray], and any other mix is
 * [IOMemento.IoString]. Container cells stay [ConfixCell]s for path access, so the column is
 * the VARIANT / jsonb cell of the SQL surfaces. A missing member or JSON null is null.
 *
 * Only the member-index table (one Int per row × column) materializes; cells reify on read.
 */
fun confixColumns(tree: Cursor, src: Series<Byte>): Cursor {
    if (tree.size == 0) return emptySeriesOf()
    val root = tree[0]
    if (root.tag != IOMemento.IoArray) return emptySeriesOf()
    val objects = root.kids
    val names = linkedMapOf<String, Int>()
    val tags = ArrayList<IOMemento?>()
    for (r in 0 until objects.size) {
        val member = objects[r]
        if (member.tag != IOMemento.IoObject) continue
        val kids = member.kids
        var i = 0
        while (i + 1 < kids.size) {
            val name = kids[i].reify(src).toString()
            val column = names.getOrPut(name) { tags.add(null); names.size }
            tags[column] = tagJoin(tags[column], memberTag(kids[i + 1], src))
            i += 2
        }
    }
    val width = names.size
    val slots = IntArray(objects.size * width) { -1 }
    for (r in 0 until objects.size) {
        val member = objects[r]
        if (member.tag != IOMemento.IoObject) continue
        val kids = member.kids
        var i = 0
        while (i + 1 < kids.size) {
            slots[r * width + names.getValue(kids[i].reify(src).toString())] = i + 1
            i += 2
        }
    }
    val metas = names.keys.mapIndexed { column, name -> ColumnMeta(name, tags[column] ?: IOMemento.IoNothing) }
    val metaSeries: Series<`ColumnMeta↻`> = width j { column: Int -> { metas[column] } }
    return objects.size j { r: Int ->
        val kids = objects[r].takeIf { it.tag == IOMemento.IoObject }?.kids
        ReifiedSplitSeries2(width j { column: Int ->
            val slot = slots[r * width + column]
            if (slot < 0 || kids == null) null else columnCell(kids[slot], src, tags[column])
        }, metaSeries)
    }
}

/**
 * A member's tag with JSON / YAML numbers refined: the text scanners tag every number
 * [IOMemento.IoDouble], so a number written without fraction or exponent answers
 * [IOMemento.IoLong]. CBOR numbers keep their binary tag (their first byte is not a digit).
 */
fun memberTag(member: RowVec, src: Series<Byte>): IOMemento {
    val tag = member.tag
    if (tag != IOMemento.IoDouble) return tag
    val first = src[member.open].toInt().toChar()
    if (first != '-' && first !in '0'..'9') return tag
    for (i in member.open..member.close) {
        val c = src[i].toInt().toChar()
        if (c != '-' && c !in '0'..'9') return tag
    }
    return IOMemento.IoLong
}

/** The column type after observing one more member tag. */
fun tagJoin(seen: IOMemento?, tag: IOMemento): IOMemento? = when {
    tag == IOMemento.IoNothing -> seen
    seen == null || seen == tag -> when (tag) { IOMemento.IoInt -> IOMemento.IoLong; else -> tag }
    seen == IOMemento.IoLong && tag == IOMemento.IoInt -> IOMemento.IoLong
    (seen == IOMemento.IoLong || seen == IOMemento.IoDouble) &&
        (tag == IOMemento.IoInt || tag == IOMemento.IoLong || tag == IOMemento.IoDouble) -> IOMemento.IoDouble
    else -> IOMemento.IoString
}

fun columnCell(member: RowVec, src: Series<Byte>, type: IOMemento?): Any? = when (member.tag) {
    IOMemento.IoNothing -> null
    IOMemento.IoObject, IOMemento.IoArray ->
        if (type == IOMemento.IoString) src.spanStr(member.open, member.close) else member j src
    else -> when (val value = member.reify(src)) {
        null -> null
        is Number -> when (type) {
            IOMemento.IoLong -> value.toLong()
            IOMemento.IoDouble -> value.toDouble()
            IOMemento.IoString -> if (memberTag(member, src) == IOMemento.IoLong) value.toLong().toString() else value.toString()
            else -> value
        }
        else -> if (type == IOMemento.IoString) value.toString() else value
    }
}
