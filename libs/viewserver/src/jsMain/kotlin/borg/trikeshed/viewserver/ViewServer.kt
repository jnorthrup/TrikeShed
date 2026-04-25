package borg.trikeshed.viewserver

import kotlin.js.JSON

fun main() {
    val registered = mutableListOf<dynamic>()

    // Use Node readline to handle JSON-lines protocol
    val rl = js("require('readline').createInterface({ input: process.stdin, output: process.stdout, terminal: false })")

    rl.on("line", { line: dynamic ->
        try {
            val cmd = JSON.parse<dynamic>(line as String)
            val op = cmd[0] as String
            when (op) {
                "reset" -> {
                    registered.clear()
                    println(JSON.stringify(true))
                }

                "add_fun" -> {
                    val src = cmd[1] as String
                    val validated = isLikelyJsFn(src)
                    if (!validated) {
                        println(JSON.stringify("error: invalid function"))
                    } else {
                        // Wrap source in parentheses to ensure it evaluates to a function object
                        val fn = kotlin.js.eval("($src)")
                        registered.add(fn)
                        println(JSON.stringify(true))
                    }
                }

                "map_doc" -> {
                    val doc = cmd[1]
                    val out = registered.map { fn: dynamic ->
                        val emitted = mutableListOf<dynamic>()
                        val emit = { k: dynamic, v: dynamic -> emitted.add(arrayOf(k, v)) }
                        try {
                            val arity = (fn.length as Int?) ?: 2
                            if (arity >= 2) fn(doc, emit) else fn(doc)
                        } catch (e: dynamic) {
                            // swallow errors for now
                        }
                        emitted.toTypedArray()
                    }
                    println(JSON.stringify(out.toTypedArray()))
                }

                else -> println(JSON.stringify("error: unknown command"))
            }
        } catch (e: dynamic) {
            println(JSON.stringify("error"))
        }
    })
}
