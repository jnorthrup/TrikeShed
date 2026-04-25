package borg.trikeshed.viewserver

import kotlin.js.JSON
import borg.trikeshed.parse.confix.contextOf
import borg.trikeshed.parse.confix.Syntax
import borg.trikeshed.parse.confix.asSeries
import borg.trikeshed.parse.json.JsContext

// A lightweight Kotlin-side map function interface that compiled views can implement
fun interface KotlinMapFunction {
    fun map(ctx: JsContext, emit: (Any?, Any?) -> Unit)
}

// Registry of named Kotlin map functions (register compiled views here before starting)
private val functionRegistry: MutableMap<String, KotlinMapFunction> = mutableMapOf()

fun registerViewFunction(name: String, fn: KotlinMapFunction) {
    functionRegistry[name] = fn
}

fun main() {
    // active functions registered by the CouchDB "add_fun" handshake (names referencing registry entries)
    val active = mutableListOf<KotlinMapFunction>()

    // Use Node readline to handle JSON-lines protocol
    val rl = js("require('readline').createInterface({ input: process.stdin, output: process.stdout, terminal: false })")

    rl.on("line", { line: dynamic ->
        try {
            val cmd = JSON.parse<dynamic>(line as String)
            val op = cmd[0] as String
            when (op) {
                "reset" -> {
                    active.clear()
                    println(JSON.stringify(true))
                }

                // Expect add_fun to provide a reference to a precompiled Kotlin function.
                // Use the convention: "@kotlin:NAME" or the exact NAME string to register.
                "add_fun" -> {
                    val ref = cmd[1] as String
                    val name = if (ref.startsWith("@kotlin:")) ref.removePrefix("@kotlin:") else ref
                    val fn = functionRegistry[name]
                    if (fn == null) {
                        println(JSON.stringify("error: unknown kotlin function '$name'"))
                    } else {
                        active.add(fn)
                        println(JSON.stringify(true))
                    }
                }

                // map_doc: receive a doc object (already parsed by outer JSON.parse). Convert back
                // to text and parse with Confix to produce a JsContext for Kotlin map functions.
                "map_doc" -> {
                    val docDyn = cmd[1]
                    val docText = JSON.stringify(docDyn) as String
                    val ctx: JsContext = contextOf(Syntax.JSON, docText.asSeries())

                    val out = active.map { fn: KotlinMapFunction ->
                        val emitted = mutableListOf<dynamic>()
                        val emitK: (Any?, Any?) -> Unit = { k, v -> emitted.add(arrayOf(k, v)) }
                        try {
                            fn.map(ctx, emitK)
                        } catch (e: dynamic) {
                            // swallow per-function errors
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
