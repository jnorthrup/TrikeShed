package borg.trikeshed.couch.internal
val unreservedChars: Set<Char> = (
    ('A'..'Z') + ('a'..'z') + ('0'..'9') + listOf('-', '_', '.', '~')
).toSet()

fun urlEncode(value: CharSequence): CharSequence {
    val out = StringBuilder()
    for (ch in value) {
        when {
            ch == ' ' -> out.append("+")
            ch in unreservedChars -> out.append(ch)
            else -> {
                for (byte in ch.toString().encodeToByteArray()) {
                    val unsigned = byte.toInt() and 0xFF
                    out.append('%')
                    out.append(unsigned.toString(16).uppercase().padStart(2, '0'))
                }
            }
        }
    }
    return out.toString()
}
