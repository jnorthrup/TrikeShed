package borg.trikeshed.graal.subvm

/** SQLite lexical boundaries used for binding and the memory-only file policy. */
object PythonSqliteSql {
    data class Token(val text: String, val start: Int, val end: Int, val quoted: Boolean = false)

    fun tokens(sql: String): List<Token> = buildList {
        var at = 0
        while (at < sql.length) {
            val begin = at
            val char = sql[at++]
            when {
                char.isWhitespace() -> Unit
                char == '-' && sql.getOrNull(at) == '-' -> {
                    while (at < sql.length && sql[at] != '\n') at++
                }
                char == '/' && sql.getOrNull(at) == '*' -> {
                    at++
                    while (at < sql.length && !(sql[at - 1] == '*' && sql[at] == '/')) at++
                    if (at < sql.length) at++
                }
                char in "'\"`[" -> {
                    val end = if (char == '[') ']' else char
                    val text = StringBuilder()
                    while (at < sql.length) {
                        val next = sql[at++]
                        if (next == end) {
                            if (char != '[' && sql.getOrNull(at) == end) { text.append(end); at++ }
                            else break
                        } else text.append(next)
                    }
                    add(Token(text.toString(), begin, at, true))
                }
                char.isLetterOrDigit() || char == '_' || char in ":@$?" -> {
                    while (at < sql.length && (sql[at].isLetterOrDigit() || sql[at] == '_')) at++
                    // SQLite permits Tcl-style $name::suffix(...) parameters.
                    if (char == '$') {
                        while (sql.getOrNull(at) == ':' && sql.getOrNull(at + 1) == ':') {
                            at += 2
                            while (at < sql.length && (sql[at].isLetterOrDigit() || sql[at] == '_')) at++
                        }
                        if (sql.getOrNull(at) == '(') {
                            while (at < sql.length && sql[at++] != ')') { }
                        }
                    }
                    add(Token(sql.substring(begin, at), begin, at))
                }
                else -> add(Token(char.toString(), begin, at))
            }
        }
    }

    fun check(sql: String, script: Boolean = false) {
        require('\u0000' !in sql) { "the query contains a null character" }
        val tokens = tokens(sql)
        tokens.forEachIndexed { index, token ->
            if (!token.quoted && token.text.equals("attach", true))
                throw UnsupportedOperationException("SQLite ATTACH is unavailable at the image boundary")
            if (!token.quoted && token.text.equals("vacuum", true) &&
                tokens.drop(index + 1).takeWhile { it.text != ";" || it.quoted }
                    .any { !it.quoted && it.text.equals("into", true) })
                throw UnsupportedOperationException("SQLite VACUUM INTO is unavailable at the image boundary")
            if (!token.quoted && token.text.equals("pragma", true)) {
                var name = index + 1
                if (tokens.getOrNull(name + 1)?.text == ".") name += 2
                when (tokens.getOrNull(name)?.text?.lowercase()) {
                    "temp_store_directory", "data_store_directory" ->
                        throw UnsupportedOperationException("SQLite native directory changes are unavailable")
                    "temp_store" -> {
                        val separator = tokens.getOrNull(name + 1)?.text
                        if (separator == "=" || separator == "(") {
                            val mode = tokens.getOrNull(name + 2)?.text?.lowercase()
                            if (mode != "2" && mode != "memory")
                                throw UnsupportedOperationException("SQLite temporary storage must remain in memory")
                        }
                    }
                }
            }
        }
        if (!script) {
            val words = tokens.filter { !it.quoted }.map { it.text.uppercase() }
            val trigger = words.take(4).let { "CREATE" in it && "TRIGGER" in it }
            var block = 0
            var ended = false
            tokens.forEach { token ->
                if (ended) throw IllegalArgumentException("You can only execute one statement at a time")
                if (trigger && !token.quoted) when (token.text.uppercase()) {
                    "BEGIN", "CASE" -> block++
                    "END" -> block--
                }
                if (token.text == ";" && !token.quoted && block <= 0) ended = true
            }
        }
    }

    /** SQLite parameter indices, including repeated names and ?NNN gaps. */
    fun parameters(sql: String): Map<Int, String?> = buildMap {
        val names = mutableMapOf<String, Int>()
        var maximum = 0
        for (token in tokens(sql)) {
            if (token.quoted || token.text.firstOrNull() !in "?:@$".toSet()) continue
            val text = token.text
            val index = when {
                text == "?" -> maximum + 1
                text[0] == '?' -> text.drop(1).toIntOrNull() ?: continue
                else -> names.getOrPut(text) { maximum + 1 }
            }
            maximum = maxOf(maximum, index)
            put(index, if (text[0] == '?') null else text.drop(1))
        }
    }

    fun command(sql: String): String? = tokens(sql).firstOrNull()?.text?.uppercase()

    fun checkReadOnly(sql: String) {
        val tokens = tokens(sql)
        tokens.forEachIndexed { index, token ->
            if (!token.quoted && token.text.equals("pragma", true)) {
                var name = index + 1
                if (tokens.getOrNull(name + 1)?.text == ".") name += 2
                if (tokens.getOrNull(name)?.text.equals("query_only", true) &&
                    tokens.getOrNull(name + 1)?.text in setOf("=", "("))
                    throw UnsupportedOperationException("read-only SQLite image leases cannot disable query_only")
            }
        }
    }
}
