package borg.trikeshed.couch.internal

val unreservedChars: Set<Char> = (
    ('A'..'Z') + ('a'..'z') + ('0'..'9') + listOf('-', '_', '.', '~')
).toSet()

internal fun urlencode(s: CharSequence): CharSequence {
    val sb = StringBuilder()
    for (ch in s) {
        when {
            ch == ' ' -> sb.append("+")
            ch in unreservedChars -> sb.append(ch)
            else -> {
                for (byte in ch.toString().encodeToByteArray()) {
                    val unsigned = byte.toInt() and 0xFF
                    sb.append('%')
                    sb.append(unsigned.toString(16).uppercase().padStart(2, '0'))
                }
            }
        }
    }
    return sb.toString()
}