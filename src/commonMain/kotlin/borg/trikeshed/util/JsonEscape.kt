package borg.trikeshed.util

fun jsonUnescape(value: String): String = buildString(value.length) {
    var index = 0
    while (index < value.length) {
        val char = value[index++]
        if (char != '\\' || index >= value.length) {
            append(char)
            continue
        }
        when (val escaped = value[index++]) {
            '"' -> append('"')
            '\\' -> append('\\')
            '/' -> append('/')
            'b' -> append('\b')
            'f' -> append('\u000C')
            'n' -> append('\n')
            'r' -> append('\r')
            't' -> append('\t')
            'u' -> {
                if (index + 4 <= value.length) {
                    val code = value.substring(index, index + 4).toIntOrNull(16)
                    if (code != null) {
                        append(code.toChar())
                        index += 4
                    } else {
                        append('\\'); append('u')
                    }
                } else {
                    append('\\'); append('u')
                }
            }
            else -> {
                append('\\'); append(escaped)
            }
        }
    }
}
