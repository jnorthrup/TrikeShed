@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package borg.trikeshed.dreamer

import borg.trikeshed.Files
import kotlin.js.JsAny
import kotlin.js.JsArray

external class Process {
    val argv: JsArray<JsAny?>
}

external val process: Process

private fun cliArgs(): List<String> {
    val argv = process.argv
    val count = (argv.length - 2).coerceAtLeast(0)
    return List(count) { index -> argv[index + 2].toString() }
}

fun main() {
    try {
        val args = parseDreamerBacktestArgs(cliArgs())
        val csvText = Files.readString(args.csvPath)
        val report = runDreamerBacktest(csvText, args)
        println(formatDreamerBacktestReport(args, report))
    } catch (e: IllegalArgumentException) {
        println(e.message ?: dreamerBacktestUsage())
        throw e
    }
}
