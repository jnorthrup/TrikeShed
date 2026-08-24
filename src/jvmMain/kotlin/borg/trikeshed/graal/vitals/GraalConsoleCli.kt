package borg.trikeshed.graal.vitals

import borg.trikeshed.parse.json.JsonSupport
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking

/**
 * GraalConsoleCli — the CLI half of the Graal console (the web half is `/graal` on the daemon).
 * Reads the same instrument cluster ([JvmVitals]) for the JVM it runs inside of.
 *
 *   vitals [--warm-ms N]   one JSON snapshot (JFR warms for N ms first, default 1500)
 *   watch                  stream compile/deopt/gc/cpu signals to stdout until interrupted
 */
object GraalConsoleCli {

    @JvmStatic
    fun main(args: Array<String>) {
        val cmd = args.firstOrNull() ?: "vitals"
        val vitals = JvmVitals().also { it.start() }
        when (cmd) {
            "vitals" -> {
                val warm = args.toList().zipWithNext().firstOrNull { it.first == "--warm-ms" }?.second?.toLongOrNull() ?: 1_500L
                Thread.sleep(warm)
                println(JsonSupport.stringify(vitals.snapshot()))
                vitals.stop()
            }
            "watch" -> {
                System.err.println("watching JIT / deopt / GC / CPU on pid ${ProcessHandle.current().pid()} — ctrl-c to stop")
                Runtime.getRuntime().addShutdownHook(Thread { vitals.stop() })
                runBlocking {
                    vitals.events.onEach { e ->
                        val color = when (e.kind) { "compile" -> 32; "deopt" -> 31; "gc" -> 33; else -> 36 }
                        println("[${color}m${e.kind.padEnd(7)}[0m ${JsonSupport.stringify(e.detail)}")
                    }.collect()
                }
            }
            else -> {
                System.err.println("Usage: GraalConsoleCli [vitals [--warm-ms N] | watch]")
                vitals.stop()
            }
        }
    }
}
