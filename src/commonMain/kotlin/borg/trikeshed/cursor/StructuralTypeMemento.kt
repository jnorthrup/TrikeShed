package borg.trikeshed.cursor

object SeqTypeMemento : TypeMemento {
    override val networkSize: Int? = null
    override fun toString(): String = "Seq"
}

object MapTypeMemento : TypeMemento {
    override val networkSize: Int? = null
    override fun toString(): String = "Map"
}

val TypeMemento.label: String
    get() = when (this) {
        SeqTypeMemento -> "Seq"
        MapTypeMemento -> "Map"
        else -> toString().substringAfterLast('.')
    }
