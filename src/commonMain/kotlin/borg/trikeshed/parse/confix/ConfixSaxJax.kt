package borg.trikeshed.parse.confix

import borg.trikeshed.cursor.IOMemento
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.Twin

sealed interface SaxEvent {
    data class Enter(val tag: IOMemento, val offset: Int) : SaxEvent
    data class Leave(val tag: IOMemento, val offset: Int) : SaxEvent
}

fun ConfixIndex.saxWalk(action: (SaxEvent) -> Unit) {
    @Suppress("UNCHECKED_CAST")
    val spans = b(ConfixIndexK.Spans) as Series<Twin<Int>>
    @Suppress("UNCHECKED_CAST")
    val tags = b(ConfixIndexK.Tags) as Series<IOMemento>
    
    val count = spans.a
    for (i in 0 until count) {
        val tag = tags.b(i)
        val twin = spans.b(i)
        val start = twin.a
        val end = twin.b
        
        action(SaxEvent.Enter(tag, start))
        action(SaxEvent.Leave(tag, end))
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
            @Suppress("UNCHECKED_CAST")
            val spans = index.b(ConfixIndexK.Spans) as Series<Twin<Int>>
            @Suppress("UNCHECKED_CAST")
            val tags = index.b(ConfixIndexK.Tags) as Series<IOMemento>
            @Suppress("UNCHECKED_CAST")
            val directChildrenFn = index.b(ConfixIndexK.DirectChildren) as (Int) -> Series<Int>
            
            if (spans.a == 0) return JaxElement(IOMemento.IoObject, 0, 0)
            
            val rootTag = tags.b(parentTokenIdx)
            val rootTwin = spans.b(parentTokenIdx)
            val rootStart = rootTwin.a
            val rootEnd = rootTwin.b
            
            val root = JaxElement(rootTag, rootStart, rootEnd)
            
            val directChildren = directChildrenFn(parentTokenIdx)
            val childCount = directChildren.a
            for (i in 0 until childCount) {
                val childIdx = directChildren.b(i)
                root.children.add(inflate(index, childIdx, src))
            }
            
            val len = rootEnd - rootStart + 1
            root.backingBytes = if (len > 0) ByteArray(len) { i -> src.b(rootStart + i) } else ByteArray(0)
            return root
        }
    }
}
