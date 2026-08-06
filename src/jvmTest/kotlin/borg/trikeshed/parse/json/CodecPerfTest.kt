package borg.trikeshed.parse.json

import kotlin.system.measureTimeMillis

fun main() {
    val input = (1..50000).map { (it % 256).toChar() }.joinToString("")

    val time = measureTimeMillis {
        val codec = CounterCodec()
        val output = codec.encode(input.asIterable()).toList()
    }
    println("Encode time: $time ms")
}
