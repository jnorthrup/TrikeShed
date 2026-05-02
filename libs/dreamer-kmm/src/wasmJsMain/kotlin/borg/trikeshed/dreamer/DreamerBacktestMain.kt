@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package borg.trikeshed.dreamer

import kotlin.js.JsAny
import kotlin.js.JsArray

external class Process {
    val argv: JsArray<JsAny?>
}

external val process: Process

external interface FsModule {
    fun readFileSync(path: String, encoding: String = definedExternally): String
}

external fun require(module: String): FsModule

private fun cliArgs(): List<String> {
    val argv = process.argv
    val count = (argv.length - 2).coerceAtLeast(0)
    return List(count) { index -> argv[index + 2].toString() }
}

private fun readCsvText(path: String): String = require("fs").readFileSync(path, "utf8")

fun main() {
    try {
        val args = parseDreamerBacktestArgs(cliArgs())
        val csvText = readCsvText(args.csvPath)
        val report = runDreamerBacktest(csvText, args)
        println(formatDreamerBacktestReport(args, report))
    } catch (e: IllegalArgumentException) {
        println(e.message ?: dreamerBacktestUsage())
        throw e
    }
}
