#!/bin/bash
sed -i 's/Character.digit(hex\[i \* 2\], 16)/hexCharToNibble(hex[i * 2])/g' src/commonMain/kotlin/borg/trikeshed/reactor/endpoint/ConfixEnvelopeCodec.kt
sed -i 's/Character.digit(hex\[i \* 2 + 1\], 16)/hexCharToNibble(hex[i * 2 + 1])/g' src/commonMain/kotlin/borg/trikeshed/reactor/endpoint/ConfixEnvelopeCodec.kt

# Wait, `hexCharToNibble` needs to be defined but it is not currently. Let's add it.
sed -i '/private fun writeInt/i \    private fun hexCharToNibble(c: Char): Int = when (c) {\n        in '\''0'\''..'\''9'\'' -> c.code - '\''0'\''.code\n        in '\''a'\''..'\''f'\'' -> c.code - '\''a'\''.code + 10\n        in '\''A'\''..'\''F'\'' -> c.code - '\''A'\''.code + 10\n        else -> throw IllegalArgumentException("Invalid hex digit: $c")\n    }\n' src/commonMain/kotlin/borg/trikeshed/reactor/endpoint/ConfixEnvelopeCodec.kt
