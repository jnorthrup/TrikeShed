package org.trikeshed.oroboros.forge

import borg.trikeshed.parse.json.JsonSupport

public interface CcekElement {
    val id: String
    fun process()
}

fun parseCcek(jsonString: String): CcekElement {
    val parsed = JsonSupport.parse(jsonString) as? Map<*, *>
    val parsedId = parsed?.get("id") as? String ?: "default"
    return object : CcekElement {
        override val id = parsedId
        override fun process() {}
    }
}
