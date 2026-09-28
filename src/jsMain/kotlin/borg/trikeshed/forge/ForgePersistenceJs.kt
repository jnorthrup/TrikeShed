package borg.trikeshed.forge

import borg.trikeshed.parse.reify
import borg.trikeshed.parse.jsonOf

@JsExport
fun parseForge(json: String): dynamic {
    return reify(json)
}

@JsExport
fun stringifyForge(obj: dynamic): String {
    return jsonOf(obj)
}
