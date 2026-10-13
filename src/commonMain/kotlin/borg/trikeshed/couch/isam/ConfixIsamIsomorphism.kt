package borg.trikeshed.couch.isam

import borg.trikeshed.isam.RecordMeta
import borg.trikeshed.isam.meta.IOMemento as IsamIOMemento
import borg.trikeshed.cursor.IOMemento as CursorIOMemento
import borg.trikeshed.cursor.Cursor
import borg.trikeshed.parse.confix.Syntax
import borg.trikeshed.lib.*
import borg.trikeshed.parse.confix.ConfixIndex
import borg.trikeshed.parse.confix.ConfixIndexK
import borg.trikeshed.parse.confix.facet

object ConfixIsamIsomorphism {

    /** Parse geometry, not an inferred application schema. Each column is independently compressed. */
    val schema: Series<RecordMeta> = s_[
        RecordMeta("open", IsamIOMemento.IoInt, 0, 4),
        RecordMeta("close", IsamIOMemento.IoInt, 4, 8),
        RecordMeta("parent", IsamIOMemento.IoInt, 8, 12),
        RecordMeta("tag", IsamIOMemento.IoString, 12, 44),
    ].also { columns -> columns.view.forEachIndexed { i, column ->
        column.groupId = i + 1
        column.groupName = column.name
    } }

    fun geometry(index: ConfixIndex): Cursor {
        val spans = index.facet(ConfixIndexK.Spans)
        val tags = index.facet(ConfixIndexK.Tags)
        val depths = index.facet(ConfixIndexK.Depths)
        val children = index.facet(ConfixIndexK.DirectChildren)
        require(tags.size == spans.size && depths.size == spans.size)
        val parents = IntArray(spans.size) { -1 }
        for (parent in 0 until spans.size) for (child in children(parent).view) {
            require(child > parent && child < spans.size && parents[child] == -1) { "Invalid Confix child ordinal" }
            require(depths[child] == depths[parent] + 1) { "Invalid Confix child depth" }
            parents[child] = parent
        }
        for (i in parents.indices) require(parents[i] >= 0 || depths[i] == 0) { "Unattached Confix token" }
        return spans.size j { i -> schema.size j { column ->
            val value: Any = when (column) {
                0 -> spans[i].a
                1 -> spans[i].b
                2 -> parents[i]
                else -> tags[i].name
            }
            value j schema[column].`↺`
        } }
    }

    /** Read once at the storage boundary; the returned tree retains no file/mapping lifetime. */
    fun index(geometry: Cursor, src: Series<Byte>, syntax: Syntax): ConfixIndex {
        val size = geometry.size
        val open = IntArray(size)
        val close = IntArray(size)
        val parent = IntArray(size)
        val depth = IntArray(size)
        val tags = arrayOfNulls<CursorIOMemento>(size)
        val starts = IntArray(size + 1)
        for (i in 0 until size) {
            val row = geometry[i]
            require(row.size == schema.size) { "Invalid Confix geometry width" }
            for (c in 0 until row.size) require(row[c].b().name == schema[c].name && row[c].b().type == schema[c].type) {
                "Invalid Confix geometry column"
            }
            open[i] = row[0].a as Int
            close[i] = row[1].a as Int
            parent[i] = row[2].a as Int
            tags[i] = CursorIOMemento.valueOf(row[3].a as String)
            val p = parent[i]
            require(p in -1 until i) { "Invalid Confix parent ordinal" }
            if (p >= 0) {
                require(open[i] >= open[p] && close[i] <= close[p]) { "Confix child exceeds parent span" }
                depth[i] = depth[p] + 1
                starts[p + 1]++
            }
        }
        for (i in 0 until size) starts[i + 1] += starts[i]
        val children = IntArray(size)
        val fill = starts.copyOf()
        for (i in 0 until size) if (parent[i] >= 0) children[fill[parent[i]]++] = i
        val flat = Syntax.FlatIndex(
            size j { i -> open[i] j close[i] },
            size j { i -> requireNotNull(tags[i]) },
            size j { i -> depth[i] },
            { i -> (starts[i + 1] - starts[i]) j { c -> children[starts[i] + c] } },
        )
        return syntax.indexed(flat, src)
    }

    /**
     * Scalar object projection only. Nested documents use [geometry], never source spans as packed offsets.
     * Since KeyToChild maps CharSequence -> Int and we cannot reverse it without reflection
     * or source bytes, we require the mapped string values explicitly to form real names.
     */
    fun inferIsamSchemaFromConfixIndex(index: ConfixIndex, keyNames: Map<Int, String>): Series<RecordMeta> {
        val tags = index.facet(ConfixIndexK.Tags)
        val spans = index.facet(ConfixIndexK.Spans)
        val directChildren = index.facet(ConfixIndexK.DirectChildren)

        val rootChildren = directChildren(0)
        val numProps = rootChildren.a / 2

        var offset = 0
        return (0 until numProps).map { p ->
            val keyIdx = rootChildren.b(p * 2)
            val valIdx = rootChildren.b(p * 2 + 1)

            val valTag = tags.b(valIdx)
            val valSpan = spans.b(valIdx)

            require(valTag != CursorIOMemento.IoArray && valTag != CursorIOMemento.IoObject && valTag != CursorIOMemento.IoNothing) {
                "Nested/nullable Confix values require the geometry projection"
            }
            val isamTag = IsamIOMemento.valueOf(valTag.name)

            val name = keyNames[keyIdx] ?: throw IllegalArgumentException("Missing key name for token index $keyIdx")

            val width = isamTag.networkSize ?: (valSpan.b - valSpan.a + 1)
            require(width > 0 && offset <= Int.MAX_VALUE - width)
            RecordMeta(
                name = name,
                type = isamTag,
                begin = offset,
                end = offset + width
            ).also { offset += width }
        }.toSeries()
    }
}
