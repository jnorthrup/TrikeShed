package org.xvm.cursor

import java.io.File
import borg.trikeshed.cursor.Cursor
import borg.trikeshed.parse.confix.ConfixLifecycle

/**
 * Blackboard Confix taxonomy timeseries runner.
 *
 * Loads P-code dump, streams ops through WireProto schemas,
 * produces nanosecond timeseries capture of the VM pointcut surface.
 */

enum class OverlayRole { OBSERVATION, AGGREGATE, ROOT }

data class PcodeOp(
    val op: String,
    val inputs: List<PcodeVarnode>?,
    val output: PcodeVarnode?
)

data class PcodeVarnode(
    val space: String,
    val offset: Long,
    val size: Int
)

data class PcodeFunction(
    val name: String,
    val entry: Long,
    val layer: String,
    val invocations: Int,
    val pcode: List<PcodeOp>
)

fun runBlackboardTimeseries(pcodePath: String) {
    val t0 = System.nanoTime()

    val json = File(pcodePath).readText()
    val functions = parsePcodeJson(json)
    val tParse = System.nanoTime()

    println("=== Blackboard Confix Taxonomy ===")
    println("Functions: ${functions.size}")
    println("Parse time: ${(tParse - t0) / 1_000_000}ms")
    println()

    // Stream ops — count and histogram
    var opCount = 0L
    val opHist = mutableMapOf<String, Long>()
    val nanoFirst = System.nanoTime()

    for (fn in functions) {
        for (pc in fn.pcode) {
            opCount++
            opHist[pc.op] = (opHist[pc.op] ?: 0L) + 1
        }
    }

    val nanoLast = System.nanoTime()

    println("=== Timeseries Capture ===")
    println("Total ops: $opCount")
    println("Stream time: ${(nanoLast - nanoFirst) / 1_000_000}ms")
    println()

    println("=== Op Histogram (Confix taxonomy) ===")
    opHist.entries.sortedByDescending { it.value }.take(20).forEach { (op, count) ->
        println("  ${String.format("%-16s", op)} $count")
    }
    println()

    // Per-function top invocation density
    println("=== Hot Functions (by invocation count) ===")
    functions.sortedByDescending { it.invocations }.take(10).forEach { fn ->
        println("  ${fn.invocations.toString().padStart(6)}  ${fn.name.take(80)}")
    }
    println()

    // Layer coverage
    val layers = functions.groupBy { it.layer }
    println("=== Layer Coverage ===")
    layers.entries.sortedByDescending { it.value.size }.forEach { (layer, fns) ->
        val ops = fns.sumOf { it.pcode.size }
        println("  ${layer.padEnd(12)} ${fns.size} functions, $ops ops")
    }

    // Varnode space histogram
    val spaceHist = mutableMapOf<String, Long>()
    for (fn in functions) {
        for (pc in fn.pcode) {
            pc.inputs?.forEach { vn -> spaceHist[vn.space] = (spaceHist[vn.space] ?: 0L) + 1 }
            pc.output?.let { vn -> spaceHist[vn.space] = (spaceHist[vn.space] ?: 0L) + 1 }
        }
    }
    println()
    println("=== Varnode Space Distribution ===")
    spaceHist.entries.sortedByDescending { it.value }.forEach { (space, count) ->
        println("  ${space.padEnd(16)} $count")
    }

    val tEnd = System.nanoTime()
    println("\nTotal: ${(tEnd - t0) / 1_000_000}ms")
}

/**
 * Parse the top-level JSON array of P-code function objects using ConfixCursor.
 * Each row yields name/entry/layer/invocations as flat fields;
 * the nested `pcode` array is extracted as a raw string and parsed
 * by a second ConfixCursor pass in [parsePcodeOps].
 */
fun parsePcodeJson(json: String): List<PcodeFunction> {
    val cursor = ConfixCursor(json, Syntax.JSON)
    val results = mutableListOf<PcodeFunction>()
    for (row in cursor.rows()) {
        try {
            val name = row.string("name") ?: continue
            val entry = row.long("entry") ?: continue
            val layer = row.string("layer") ?: "UNKNOWN"
            val invocations = row.int("invocations") ?: 0

            val pcodeRaw = row.get("pcode")?.toString() ?: "[]"
            val ops = parsePcodeOps(pcodeRaw)

            results.add(PcodeFunction(name, entry, layer, invocations, ops))
        } catch (_: Exception) { continue }
    }
    return results
}

/**
 * Parse the nested pcode ops array with a second ConfixCursor.
 * ConfixCursor captures nested objects/arrays as raw strings, so
 * inputs and output varnodes are parsed with the existing regex helpers.
 */
private fun parsePcodeOps(pcodeJson: String): List<PcodeOp> {
    val trimmed = pcodeJson.trim()
    if (!trimmed.startsWith("[")) return emptyList()
    val ops = mutableListOf<PcodeOp>()
    val pcodeCursor = ConfixCursor(pcodeJson, Syntax.JSON)
    for (opRow in pcodeCursor.rows()) {
        try {
            val op = opRow.string("op") ?: continue
            val inputStr = opRow.get("inputs")?.toString()
            val outputStr = opRow.get("output")?.toString()
            val inputs = if (inputStr == null) null else parseVarnodes(inputStr)
            val output = if (outputStr == null) null else parseVarnode(outputStr)
            ops.add(PcodeOp(op, inputs, output))
        } catch (_: Exception) { continue }
    }
    return ops
}

private fun parseVarnode(s: String): PcodeVarnode {
    val space = Regex(""""space"\s*:\s*"([^"]*)"""").find(s)?.groupValues?.get(1) ?: "const"
    val offset = Regex(""""offset"\s*:\s*(\d+)""").find(s)?.groupValues?.get(1)?.toLong() ?: 0L
    val size = Regex(""""size"\s*:\s*(\d+)""").find(s)?.groupValues?.get(1)?.toInt() ?: 0
    return PcodeVarnode(space, offset, size)
}

private fun parseVarnodes(s: String): List<PcodeVarnode> {
    val result = mutableListOf<PcodeVarnode>()
    val vnPattern = Regex("""\{\s*"space"\s*:\s*"([^"]*)"\s*,\s*"offset"\s*:\s*(\d+)\s*,\s*"size"\s*:\s*(\d+)\s*\}""")
    for (m in vnPattern.findAll(s)) {
        result.add(PcodeVarnode(m.groupValues[1], m.groupValues[2].toLong(), m.groupValues[3].toInt()))
    }
    return result
}

fun main() {
    val pcodePath = "/Users/jim/work/xvm/lib_cursor/typeconstant-pcode.json"
    runBlackboardTimeseries(pcodePath)
}
