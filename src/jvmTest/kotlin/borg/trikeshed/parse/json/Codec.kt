package borg.trikeshed.parse.json

// Define the Codec interface
interface Codec<I, O> {
    fun encode(input: Iterable<I>): Sequence<O>
    fun decode(output: Iterable<O>): Sequence<I>
}

// Implement the Codec for null processing
class CounterCodec : Codec<Char, Int> {
    val counters = mutableMapOf<Char, Int>()
    override fun encode(input: Iterable<Char>): Sequence<Int> = sequence {
        for (c in input) { //count

            (counters.getOrDefault(c, 0) + 1).also { counters[c] = it }

            yield(c.code)
        }
    }

    override fun decode(output: Iterable<Int>): Sequence<Char> = sequence { for (c in output) yield(c.toChar()) }
}

fun main() {
    val codec = CounterCodec()
    val input = listOf("Codec", "example", "Iterable", "Sequence").toString()


    println("Input: $input")

    // Encode input to output
    val output = codec.encode("$input".asIterable())
    println("Output: ${output.toList()}")

    // Decode output to round-trip
    val roundTrip = codec.decode(output.asIterable()).asIterable().joinToString("")
    println("Round-trip: ${roundTrip}")

    // Verify if input and round-trip are the same
    println("Input and round-trip are the same: ${input == roundTrip}")
}
