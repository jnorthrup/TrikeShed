package borg.trikeshed.miniduck

/**
 * Escape a string for JSON output.
 */
internal fun escapeJson(s: String): String {
    val sb = StringBuilder(s.length)
    for (c in s) {
        when (c) {
            '\\' -> sb.append("\\\\")
            '"' -> sb.append("\\\"")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            else -> {
                if (c.code < 0x20) {
                    sb.append("\\u${c.code.toString(16).padStart(4, '0')}")
                } else {
                    sb.append(c)
                }
            }
        }
    }
    return sb.toString()
}
