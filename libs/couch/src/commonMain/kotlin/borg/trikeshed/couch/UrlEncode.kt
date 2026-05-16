package borg.trikeshed.couch

internal fun urlencode(s: CharSequence): CharSequence {
    val bytes = s.toString().encodeToByteArray()
    val sb = StringBuilder()
    for (b in bytes) {
        val c = b.toInt() and 0xFF
        val ch = c.toChar()
        if ((c in 'a'.code..'z'.code) || (c in 'A'.code..'Z'.code) || (c in '0'.code..'9'.code) || c == '-'.code || c == '_'.code || c == '.'.code || c == '~'.code) {
            sb.append(ch)
        } else {
            sb.append('%')
            sb.append(c.toString(16).uppercase().padStart(2, '0'))
        }
    }
    return sb.toString()
}
