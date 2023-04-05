package borg.trikeshed.parse

/**
json bitmap simd codifier

starting with the premise that we might be able to identify character classes of a 400GB streaming json gzip decompressor: we can look at the relevant json scoping operators and come up with a mask for 3 classes of delimiters:


|char| ascii | binary |
|---|---|---|
|{ | 123 | 0b01111011 |
|[ |  91 | 0b01011011 |
|} | 125 | 0b01111101 |
|] |  93 | 0b01011101 |
|, |  44 | 0b00101100 |
|: |  58 | 0b00111010 |
|" |  34 | 0b00100010 |

|{ | 123 | 0b01x11011 |
|[ |  91 | 0b01x11011 |
|} | 125 | 0b01x11101 |
|] |  93 | 0b01x11101 |
|, |  44 | 0b00x01100 |

binary:  `{` & '[' & ',' :
Yes, you're correct. In my previous examples, the bitmap was represented as a single integer, which can only store the classes of a limited number of bytes. For a 32k input buffer, you would need a larger bitmap to store the classes of all bytes in the buffer.

One way to represent the bitmap for a 32k input buffer is to use an array of integers instead of a single integer. Each integer in the array can store the classes of up to 16 bytes (assuming 2 bits per byte), so you would need an array of size `32k / 16 = 2k` to represent the entire bitmap.

Here's an example that shows how you could generate a bitmap for a 32k input buffer using an array of integers:

```kotlin
val input = ByteArray(32 * 1024) // 32k input buffer
val bitmap = IntArray(2 * 1024) // 2k bitmap

for (i in input.indices) {
val byte = input[i].toInt() and 0xFF
val mask1 = 0b01011011
val mask2 = 0b01011101
val mask3 = 0b00101100

val class1 = ((byte xor mask1) and mask1.inv()) == 0
val class2 = ((byte xor mask2) and mask2.inv()) == 0
val class3 = (byte == mask3)

val index = i / 16
val shift = (i % 16) * 2
bitmap[index] = bitmap[index] xor (class1.toInt() xor (class2.toInt() shl 1) xor (class3.toInt() shl 1)) shl shift
}

println(bitmap.joinToString("") { Integer.toBinaryString(it) })
```

This code processes a static input buffer of size 32k and generates a bitmap that encodes the classes of all bytes in the buffer. It uses an array of integers to represent the bitmap and updates the corresponding integer in the array for each byte in the input buffer.

Is this closer to what you had in mind?

To iterate over the bitmap and extract the classes of the bytes in the input buffer, you could use a loop to process each integer in the bitmap array and extract the corresponding classes using bitwise operations. Here's an example that shows how you could do this in Kotlin:

```kotlin
val classes = IntArray(input.size)

for (i in bitmap.indices) {
val value = bitmap[i]
for (j in 0 until 16) {
val shift = j * 2
val class = (value shr shift) and 0b11
val index = i * 16 + j
classes[index] = class
}
}

println(classes.joinToString())
```

This code iterates over the bitmap array and extracts the classes of all bytes in the input buffer. It uses bitwise shift and AND operations to extract the class of each byte from the corresponding integer in the bitmap array.

This approach should be amenable to auto-vectorization by most modern compilers, as it only uses simple arithmetic and bitwise operations and does not contain any branching or function calls.

Is this what you were asking about?


You're right, the current implementation of the `escapeCounter` update only handles the case where the UTF8 initiator is a single byte (0b110xxxxx). To handle UTF8 initiators of 1 to 3 bytes of continuation (0b110xxxxx, 0b1110xxxx, and 0b11110xxx), you could modify the code that updates the `escapeCounter` variable to take into account the different possible values of the UTF8 initiator. Here's one way you could do this:

```kotlin
val escapeMask = ((byte xor '\\'.toInt()) + 1 shr 8) or
((byte and 0b11100000) xor 0b11000000 shr 5) or
((byte and 0b11110000) xor 0b11100000 shr 4) or
((byte and 0b11111000) xor 0b11110000 shr 3)
escapeCounter = (escapeCounter or escapeMask) and (escapeCounter - 1 or -escapeMask)
```

This code will update the `escapeCounter` variable to handle UTF8 initiators of 1 to 3 bytes of continuation. It uses bitwise operations to check for each of the possible values of the UTF8 initiator and sets the `escapeMask` variable accordingly.

Here's the updated version of the code with this change applied and additional comments added for clarity:

```kotlin
val input = ByteArray(32 * 1024) // 32k input buffer
val bitmap = IntArray(2 * 1024) // 2k bitmap

var quoteState = 0 // State variable to keep track of whether we are inside a quoted string
var escapeCounter = 0 // Counter variable to keep track of escape characters and UTF8 initiators

for (i in input.indices) {
val byte = input[i].toInt() and 0xFF

// Update quoteState using bitwise operations
val quoteMask = (byte xor '"'.toInt()) + 1 shr 8
quoteState = quoteState xor quoteMask

// Update escapeCounter using bitwise operations to handle escape characters and UTF8 initiators of different lengths
val escapeMask = ((byte xor '\\'.toInt()) + 1 shr 8) or
((byte and 0b11100000) xor 0b11000000 shr 5) or
((byte and 0b11110000) xor 0b11100000 shr 4) or
((byte and 0b11111000) xor 0b11110000 shr 3)
escapeCounter = (escapeCounter or escapeMask) and (escapeCounter - 1 or -escapeMask)

val mask1 = 0b01011011
val mask2 = 0b01011101
val mask3 = 0b00101100

// Apply damping mask using bitwise operations
val dampingMask = quoteState - 1 shr 31
val class1 = (((byte xor mask1) and mask1.inv()) == 0) and dampingMask
val class2 = (((byte xor mask2) and mask2.inv()) == 0) and dampingMask
val class3 = (byte == mask3) and dampingMask

// Update bitmap using bitwise operations
val index = i / 16
val shift = (i % 16) * 2
bitmap[index] = bitmap[index] xor (class1.toInt() xor (class2.toInt() shl 1) xor (class3.toInt() shl 1)) shl shift
}
```
 */
//
//To ensure that the `escapeCounter` variable is not incremented during an `escapeCounter` countdown without using conditionals, you could modify the code that updates the `escapeCounter` variable to take into account the current value of the `escapeCounter` variable when computing the `escapeMask`. Here's one way you could do this:
//
//```kotlin
//val escapeMask = (((byte xor '\\'.toInt()) + 1 shr 8) or
//        ((byte and 0b11100000) xor 0b11000000 shr 5) or
//        ((byte and 0b11110000) xor 0b11100000 shr 4) or
//        ((byte and 0b11111000) xor 0b11110000 shr 3)) and
//        (escapeCounter - 1 shr 31)
//escapeCounter = (escapeCounter or escapeMask) and (escapeCounter - 1 or -escapeMask)
//```
//
//This code computes the `escapeMask` variable in the same way as before, but adds an additional bitwise operation at the end to mask out the `escapeMask` variable when the `escapeCounter` variable is non-zero. This ensures that the `escapeCounter` variable is not incremented during an `escapeCounter` countdown.
//
//Here's the updated version of the code with this change applied:
//
//```kotlin
fun encodeJsonBitmap(input: ByteArray, bitmap: IntArray, quoteState: Int, escapeCounter: Int): Pair<Int, Int> {
    var quoteState = quoteState
    var escapeCounter = escapeCounter

    for (i in input.indices) {
        val byte = input[i].toInt() and 0xFF

        // Update quoteState using bitwise operations
        val quoteMask = (byte xor '"'.code) + 1 shr 8
        quoteState = quoteState xor quoteMask

        // Update escapeCounter using bitwise operations to handle escape characters and UTF8 initiators of different lengths
        val escapeMask = (((byte xor '\\'.code) + 1 shr 8) or
                ((byte and 0b11100000) xor 0b11000000 shr 5) or
                ((byte and 0b11110000) xor 0b11100000 shr 4) or
                ((byte and 0b11111000) xor 0b11110000 shr 3)) and
                (escapeCounter - 1 shr 31)
        escapeCounter = (escapeCounter or escapeMask) and (escapeCounter - 1 or -escapeMask)

        val mask1 = 0b01011011
        val mask2 = 0b01011101
        val mask3 = 0b00101100

        // Apply damping mask using bitwise operations
        val dampingMask = quoteState - 1 shr 31
        val class1 = (((byte xor mask1) and mask1.inv()) + 1 ushr 31) and dampingMask.inv()
        val class2 = (((byte xor mask2) and mask2.inv()) + 1 ushr 31) and dampingMask.inv()
        val class3 = ((byte xor mask3) + 1 ushr 31) and dampingMask.inv()

        // Update bitmap using bitwise operations
        val index = i / 16
        val shift = (i % 16) * 2
        bitmap[index] =
            bitmap[index] xor (class1.toInt() xor (class2.toInt() shl 1) xor (class3.toInt() shl 1)) shl shift
    }

    return Pair(quoteState, escapeCounter)
}
//```
//
//This version of the code should ensure that the `escapeCounter` variable is not incremented during an `escapeCounter` countdown without using conditionals.
//
//I hope this helps! Let me know if you have any questions or if there's anything else I can help with.

//Yes, to handle UTF-8 characters correctly, you would need to parse the UTF-8 initiators to determine the number of bytes to skip and update the bitmap accordingly. Here is an updated version of the `update_bitmap` function that handles UTF-8 characters:
const val openBit = '{'.code.toByte()
const val closeBit = '}'.code.toByte()
const val arrayOpenBit = '['.code.toByte()
const val arrayCloseBit = ']'.code.toByte()
const val valueDelimiterBit = ','.code.toByte()
const val quoteBit = '"'.code.toByte()
const val escapeBit = '\\'.code.toByte()

const val utf8Bit2 = 0b11000000 shr 5
const val utf8Bit3 = 0b11100000 shr 4
const val utf8Bit4 = 0b11110000 shr 3


enum class State { EMPTY, OPEN, CLOSE, DELIMITER }
enum class BitPlane { state1, state2, quote, escape }

fun update_bitmap(buffer: ByteArray, bitmap: IntArray) {

    var i = 0
    var in_quotes = 0
    var escape_count = 0
    var utf8_count = 0
    while (i < buffer.size) {
        val is_escape = (buffer[i].toInt() xor escapeBit) or (in_quotes xor 1)
        escape_count = escape_count xor (escape_count and -is_escape.inv()) or (2 and is_escape.inv())
        escape_count = escape_count - (escape_count xor 0).inv()

// replace conditional statements with bitwise operations
        utf8_count = utf8_count or (((buffer[i].toInt() and 0b11100000) xor 0b11000000).inv() and 1)
        utf8_count = utf8_count or (((buffer[i].toInt() and 0b11110000) xor 0b11100000).inv() and 2)
        utf8_count = utf8_count or (((buffer[i].toInt() and 0b11111000) xor 0b11110000).inv() and 3)
        utf8_count =
            utf8_count - (((buffer[i].toInt() and 0b11000000) xor 0b10000000).inv() and /*(utf8_count > 0) rewrite as bitwise operators: */ (utf8_count - 1 shr 31))

        val is_quote = buffer[i].toInt() xor quoteBit
        in_quotes = in_quotes xor is_quote.inv()
        val not_in_quotes = in_quotes xor 1
        val open_mask =
            (((buffer[i].toInt() xor openBit) and (buffer[i].toInt() xor arrayOpenBit)).inv() and not_in_quotes) shl OPEN_BIT
        val close_mask =
            (((buffer[i].toInt() xor closeBit) and (buffer[i].toInt() xor arrayCloseBit)).inv() and not_in_quotes) shl CLOSE_BIT
        val value_delimiter_mask =
            ((buffer[i].toInt() xor valueDelimiterBit).inv() and not_in_quotes) shl VALUE_DELIMITER_BIT
        bitmap[i] = bitmap[i] or open_mask or close_mask or value_delimiter_mask
        i++
    }
}