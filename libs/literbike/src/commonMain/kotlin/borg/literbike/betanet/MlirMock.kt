package borg.literbike.betanet

/**
 * MLIR mock for TDD - lightweight compile + fallback interpreter
 *
 * A tiny mock that pretends to "compile" a matching function from MLIR source.
 * For TDD we treat MLIR source containing the token `compile_ok` as compilable.
 * Ported from literbike/src/betanet/mlir_mock.rs
 */

sealed class MlirError(message: String) : Exception(message) {
    data class CompileError(override val message: String) : MlirError(message)
}

class CompiledMatcher(
    private val matcher: (ByteArray) -> Boolean,
) {
    fun run(data: ByteArray): Boolean = matcher(data)
}

/**
 * Try to compile MLIR text into a CompiledMatcher. In this mock, if the text
 * contains `compile_ok` we return a fast matcher that checks for a u64 pattern
 * embedded as hex literal `0x1122334455667788` inside the source. Otherwise fail.
 */
fun compileMlir(src: String): Result<CompiledMatcher> {
    return if ("compile_ok" in src) {
        val defaultPattern = 0x1122_3344_5566_7788L
        val pat = if ("0x" in src) {
            val idx = src.indexOf("0x")
            val snippet = src.substring(idx)
            var end = 2
            for (i in 2 until snippet.length) {
                if (snippet[i].isHexDigit()) {
                    end++
                } else {
                    break
                }
            }
            val lit = snippet.substring(0, end)
            lit.substring(2).toLongOrNull(16) ?: defaultPattern
        } else {
            defaultPattern
        }

        val matcher: (ByteArray) -> Boolean = { data ->
            if (data.size < 8) return@matcher false
            val w = ((data[0].toLong() and 0xFF) shl 56) or
                    ((data[1].toLong() and 0xFF) shl 48) or
                    ((data[2].toLong() and 0xFF) shl 40) or
                    ((data[3].toLong() and 0xFF) shl 32) or
                    ((data[4].toLong() and 0xFF) shl 24) or
                    ((data[5].toLong() and 0xFF) shl 16) or
                    ((data[6].toLong() and 0xFF) shl 8) or
                    (data[7].toLong() and 0xFF)
            w == pat
        }

        Result.success(CompiledMatcher(matcher))
    } else {
        Result.failure(MlirError.CompileError("mock compile failure"))
    }
}

/**
 * Interpreter fallback: looks for a hex literal in the source and scans data
 * for that u64 pattern; returns whether it was found.
 */
fun interpretMlir(src: String, data: ByteArray): Boolean {
    val defaultPattern = 0x1122_3344_5566_7788L
    val pat = if ("0x" in src) {
        val idx = src.indexOf("0x")
        val snippet = src.substring(idx)
        var end = 2
        for (i in 2 until snippet.length) {
            if (snippet[i].isHexDigit()) {
                end++
            } else {
                break
            }
        }
        val lit = snippet.substring(0, end)
        lit.substring(2).toLongOrNull(16) ?: defaultPattern
    } else {
        defaultPattern
    }

    if (data.size < 8) return false
    return data.windowed(8).any { window ->
        val w = ((window[0].toLong() and 0xFF) shl 56) or
                ((window[1].toLong() and 0xFF) shl 48) or
                ((window[2].toLong() and 0xFF) shl 40) or
                ((window[3].toLong() and 0xFF) shl 32) or
                ((window[4].toLong() and 0xFF) shl 24) or
                ((window[5].toLong() and 0xFF) shl 16) or
                ((window[6].toLong() and 0xFF) shl 8) or
                (window[7].toLong() and 0xFF)
        w == pat
    }
}

private fun Char.isHexDigit(): Boolean = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'
