package borg.trikeshed.parse.confix

import borg.trikeshed.cursor.IOMemento
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.Twin
import borg.trikeshed.lib.get
import borg.trikeshed.lib.size
import borg.trikeshed.lib.view

sealed interface SaxEvent {
    data class Enter(val tag: IOMemento, val offset: Int) : SaxEvent
    data class Leave(val tag: IOMemento, val offset: Int) : SaxEvent
}

fun ConfixIndex.saxWalk(action: (SaxEvent) -> Unit) {
    val spans: Series<Twin<Int>> = facet(ConfixIndexK.Spans)
    val tags: Series<IOMemento> = facet(ConfixIndexK.Tags)
    spans.view.zip(tags.view).forEach { (twin, tag) ->
        action(SaxEvent.Enter(tag, twin.a))
        action(SaxEvent.Leave(tag, twin.b))
    }
}

class JaxElement(val tag: IOMemento, val startIndex: Int, val endIndex: Int) {
    val children = mutableListOf<JaxElement>()
    private var backingBytes: ByteArray? = null

    fun bytes(): ByteArray {
        return backingBytes ?: ByteArray(0)
    }

    companion object {
        fun inflate(index: ConfixIndex, parentTokenIdx: Int, src: Series<Byte>): JaxElement {
            val spans: Series<Twin<Int>> = index.facet(ConfixIndexK.Spans)
            val tags: Series<IOMemento> = index.facet(ConfixIndexK.Tags)
            val directChildrenFn: (Int) -> Series<Int> = index.facet(ConfixIndexK.DirectChildren)

            if (spans.size == 0) return JaxElement(IOMemento.IoObject, 0, 0)

            val rootTag = tags[parentTokenIdx]
            val rootTwin = spans[parentTokenIdx]
            val rootStart = rootTwin.a
            val rootEnd = rootTwin.b
            val root = JaxElement(rootTag, rootStart, rootEnd)

            val directChildren = directChildrenFn(parentTokenIdx)
            val childCount = directChildren.size
            for (i in 0 until childCount) {
                val childIdx = directChildren[i]
                root.children.add(inflate(index, childIdx, src))
            }

            val len = rootEnd - rootStart + 1
            root.backingBytes = if (len > 0) ByteArray(len) { i -> src[rootStart + i] } else ByteArray(0)
            return root
        }
    }
}
