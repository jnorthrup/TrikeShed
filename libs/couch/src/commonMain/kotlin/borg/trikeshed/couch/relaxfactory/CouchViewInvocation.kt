package borg.trikeshed.couch.relaxfactory

import borg.trikeshed.couch.internal.urlEncode

class CouchViewInvocation(
    val path: CharSequence,
    val template: CharSequence,
    val returnShape: CouchViewInvocation.ReturnShape,
   val encodeValue: (Any?) -> CharSequence,
   val databaseName: CharSequence = "",
) {
    enum class ReturnShape {
        ListValue,
        MapKeyValue,
        ScalarValue,
    }

    fun invoke(vararg args: Any?): CouchViewInvocation {
        val encoded = args.mapIndexed { i, arg ->
            val encodedVal = urlEncode(encodeValue(arg))
            "%${i + 1}\$s" to encodedVal
        }.toMap()
        // Replace %N$s placeholders with encoded values
        var resolved = template
        for ((placeholder, value) in encoded) {
            resolved = resolved.replace(placeholder, value)
        }
        val fullPath = if (databaseName.isNotEmpty()) "/$databaseName/$resolved" else resolved
        return CouchViewInvocation(
            path = fullPath,
            template = template,
            returnShape = returnShape,
            encodeValue = encodeValue,
            databaseName = databaseName,
        )
    }
}
