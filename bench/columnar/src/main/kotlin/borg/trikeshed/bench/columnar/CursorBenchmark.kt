package borg.trikeshed.bench.columnar

import borg.trikeshed.cursor.*
import borg.trikeshed.lib.*
import java.io.File
import java.lang.management.ManagementFactory
import kotlin.system.measureNanoTime

/** Deterministic synthetic sparse pivot/reduction. Every resulting cell is checked against integer sums. */
fun main(args: Array<String>) {
    val rows = args.getOrElse(0) { "65536" }.toInt()
    val warmup = args.getOrElse(1) { "3" }.toInt()
    val iterations = args.getOrElse(2) { "7" }.toInt()
    require(rows in 256..10_000_000 && warmup >= 0 && iterations > 0)
    val groups = 64
    val categories = 4
    val meta = s_[ColumnMeta("group", IOMemento.IoInt), ColumnMeta("category", IOMemento.IoInt), ColumnMeta("amount", IOMemento.IoDouble)]
    val source: Cursor = rows j { row ->
        3 j { column ->
            val value: Any = when (column) {
                0 -> row % groups
                1 -> (row / groups) % categories
                else -> ((row * 17L + 3L) % 101L - 50L) / 64.0
            }
            value j { meta[column] }
        }
    }
    // Independent reference uses integer arithmetic, never Cursor grouping/pivot routines.
    val expected = LongArray(groups * categories)
    for (row in 0 until rows) expected[(row % groups) * categories + (row / groups) % categories] += (row * 17L + 3L) % 101L - 50L
    val sum: RowReducer = { acc, value -> (acc as? Double ?: 0.0) + (value as? Double ?: 0.0) }
    val samples = LongArray(iterations)
    var last = DoubleArray(0)
    fun execute(): DoubleArray {
        val result = source.pivot(intArrayOf(0), intArrayOf(1), intArrayOf(2)).groupBy(intArrayOf(0), sum)
        check(result.size == groups && result.width == categories + 1)
        return DoubleArray(groups * categories) { index ->
            val group = index / categories
            check(result[group][0].a == group)
            result[group][index % categories + 1].a as Double
        }
    }
    fun verify(actual: DoubleArray) {
        for (i in expected.indices) check(actual[i] == expected[i] / 64.0) { "Cursor result[$i]=${actual[i]}, expected ${expected[i] / 64.0}" }
    }
    repeat(warmup) { verify(execute()) }
    repeat(iterations) { i -> samples[i] = measureNanoTime { last = execute() }; verify(last) }
    // Name projection and metadata travel through the same algebra used by consumers.
    val projection = source.select("amount", "group")
    check(projection[0][0].b().name == "amount" && projection[0][1].b().type == IOMemento.IoInt)
    val report = """
        {
          "workload": "cursor_sparse_pivot_group_sum",
          "rows": $rows, "groups": $groups, "categories": $categories,
          "input": "group=r%64; category=(r/64)%4; amount=((r*17+3)%101-50)/64",
          "warmup": $warmup, "iterations": $iterations,
          "compute_ns": [${samples.joinToString()}],
          "median_ns": ${samples.sorted()[samples.size / 2]},
          "verified_cells": ${expected.size}, "checksum": ${last.sum()},
          "verification": "exact Float64 equality with independent integer reference",
          "timed_boundary": "lazy values + pivot key scan + group index + shape checks + full result materialization; integer-reference comparison excluded",
          "process_uptime_ms_at_report": ${ManagementFactory.getRuntimeMXBean().uptime},
          "java": "${System.getProperty("java.version")}",
          "vm": "${System.getProperty("java.vm.name")}",
          "os": "${System.getProperty("os.name")}", "arch": "${System.getProperty("os.arch")}",
          "processors": ${Runtime.getRuntime().availableProcessors()}
        }
    """.trimIndent()
    val output = File(args.getOrElse(3) { "results/cursor.json" })
    output.parentFile?.mkdirs()
    output.writeText(report + "\n")
    println(report)
}
