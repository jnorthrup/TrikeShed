package borg.trikeshed.couch.isam

import borg.trikeshed.isam.RecordMeta
import borg.trikeshed.isam.meta.IOMemento as IsamIOMemento
import borg.trikeshed.cursor.IOMemento as CursorIOMemento
import borg.trikeshed.lib.*
import borg.trikeshed.parse.confix.ConfixIndex
import borg.trikeshed.parse.confix.ConfixIndexK
import borg.trikeshed.parse.confix.facet

object ConfixIsamIsomorphism {

    /**
<<<<<<< HEAD
     * Extracts a flat ISAM schema from a Confix index. 
     * Since KeyToChild maps CharSequence -> Int and we cannot reverse it without reflection 
=======
     * Extracts a flat ISAM schema from a Confix index.
     * Since KeyToChild maps CharSequence -> Int and we cannot reverse it without reflection
>>>>>>> origin/confix-isam-schema-inference-14688054800249381584
     * or source bytes, we require the mapped string values explicitly to form real names.
     */
    fun inferIsamSchemaFromConfixIndex(index: ConfixIndex, keyNames: Map<Int, String>): Series<RecordMeta> {
        val tags = index.facet(ConfixIndexK.Tags)
        val spans = index.facet(ConfixIndexK.Spans)
        val directChildren = index.facet(ConfixIndexK.DirectChildren)

        val rootChildren = directChildren(0)
        val numProps = rootChildren.a / 2

        return numProps j { p ->
            val keyIdx = rootChildren.b(p * 2)
            val valIdx = rootChildren.b(p * 2 + 1)
<<<<<<< HEAD
            
            val valTag = tags.b(valIdx)
            val valSpan = spans.b(valIdx)
            
            val isamTag = IsamIOMemento.valueOf(valTag.name)
            
            val name = keyNames[keyIdx] ?: throw IllegalArgumentException("Missing key name for token index $keyIdx")
            
=======

            val valTag = tags.b(valIdx)
            val valSpan = spans.b(valIdx)

            val isamTag = IsamIOMemento.valueOf(valTag.name)

            val name = keyNames[keyIdx] ?: throw IllegalArgumentException("Missing key name for token index $keyIdx")

>>>>>>> origin/confix-isam-schema-inference-14688054800249381584
            RecordMeta(
                name = name,
                type = isamTag,
                begin = valSpan.a,
                end = valSpan.b
            )
        }
    }
}
