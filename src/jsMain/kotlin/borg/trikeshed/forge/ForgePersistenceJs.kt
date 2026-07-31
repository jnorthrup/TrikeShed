package borg.trikeshed.forge

import borg.trikeshed.parse.json.JsonSupport

@JsExport
fun parseForge(json: String): dynamic {
    return JsonSupport.parse(json)
}

@JsExport
fun stringifyForge(obj: dynamic): String {
    return JsonSupport.stringify(obj)
}
