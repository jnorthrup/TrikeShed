package borg.trikeshed.parse.confix

sealed class ConfixElement

object ConfixNull : ConfixElement()

class ConfixPrimitive(
    val content: String,
    val isString: Boolean
) : ConfixElement() {
    constructor(b: Boolean) : this(b.toString(), false)
    constructor(n: Number) : this(n.toString(), false)
    
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ConfixPrimitive) return false
        return content == other.content && isString == other.isString
    }
    
    override fun hashCode(): Int {
        var result = content.hashCode()
        result = 31 * result + isString.hashCode()
        return result
    }
    
    override fun toString(): String = if (isString) "\"$content\"" else content
    
    val booleanOrNull: Boolean? get() = when (content) {
        "true" -> true
        "false" -> false
        else -> null
    }
}

class ConfixArray(
    val elements: List<ConfixElement>
) : ConfixElement(), List<ConfixElement> by elements {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ConfixArray) return false
        return elements == other.elements
    }
    override fun hashCode(): Int = elements.hashCode()
    override fun toString(): String = elements.toString()
}

class ConfixObject(
    val content: Map<String, ConfixElement>
) : ConfixElement(), Map<String, ConfixElement> by content {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ConfixObject) return false
        return content == other.content
    }
    override fun hashCode(): Int = content.hashCode()
    override fun toString(): String = content.toString()
}
