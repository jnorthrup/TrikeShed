package borg.trikeshed.cursor

import kotlin.system.measureTimeMillis

fun main() {
    val rows = 10000
    val cols = 1000
    val values = DoubleArray(rows * cols) { it.toDouble() }

    val wasm = WasmDoubleTensor(rows, cols, values)

    // warmup
    for (i in 0 until 1000) {
        wasm.columnMean(100)
    }

    val time = measureTimeMillis {
        for (i in 0 until 10000) {
            wasm.columnMean(100)
        }
    }
    println("Time: $time ms")
}
