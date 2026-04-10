package borg.literbike.betanet

/**
 * SIMD-first detection scaffold with policy.
 * Ported from literbike/src/betanet/simd_match.rs.
 */

/**
 * Detect with policy: prefers SIMD path when available, falls back to scalar.
 */
fun detectWithPolicy(anchors: List<Anchor>, data: ByteArray): Anchor? {
    return if (hasAvx2()) {
        simdDetect(anchors, data)
    } else {
        ProtocolDetector(anchors).detect(data)
    }
}

private fun simdDetect(anchors: List<Anchor>, data: ByteArray): Anchor? {
    if (data.size < 8) return null

    var best: Anchor? = null

    for (windowStart in 0 until data.size - 7) {
        val window = data.copyOfRange(windowStart, windowStart + 8)
        for (a in anchors) {
            if (a.matches(window)) {
                if (best == null || a.priority > best.priority) {
                    best = a
                }
            }
        }
    }

    return best
}

/**
 * Detection pipeline: SIMD anchors -> MLIR compiled -> MLIR interpreted fallback.
 * Ported from literbike/src/betanet/detector_pipeline.rs.
 */
sealed class Detection {
    data class AnchorMatch(val anchor: Anchor) : Detection()
    data object MlirCompiled : Detection()
    data object MlirInterpreted : Detection()
}

/**
 * Full detection pipeline with MLIR support.
 */
fun detectPipeline(
    anchors: List<Anchor>,
    data: ByteArray,
    mlirSrc: String? = null
): Detection? {
    // Step 1: SIMD/anchor detection
    val anchorResult = detectWithPolicy(anchors, data)
    if (anchorResult != null) {
        return Detection.AnchorMatch(anchorResult)
    }

    // Step 2: MLIR compiled matcher
    mlirSrc?.let { src ->
        if (hasMlir()) {
            when (val result = compileMlir(src)) {
                is MlirResult.Success -> {
                    if (result.matcher(data)) {
                        return Detection.MlirCompiled
                    }
                }
                is MlirResult.Failure -> {
                    // Fall back to interpreter
                    if (interpretMlir(src, data)) {
                        return Detection.MlirInterpreted
                    }
                }
            }
        } else {
            // MLIR not available, try interpreter directly
            if (interpretMlir(src, data)) {
                return Detection.MlirInterpreted
            }
        }
    }

    return null
}

// ============================================================================
// Capability checks
// ============================================================================

private fun hasAvx2(): Boolean {
    System.getenv("BETANET_FORCE_AVX2")?.let {
        return it in listOf("1", "true", "yes")
    }
    val arch = System.getProperty("os.arch").lowercase()
    return "amd64" in arch || "x86_64" in arch
}

private fun hasMlir(): Boolean {
    System.getenv("BETANET_FORCE_MLIR")?.let {
        return it in listOf("1", "true", "yes")
    }
    return false
}

// ============================================================================
// MLIR mock (port of mlir_mock.rs)
// ============================================================================

sealed class MlirResult {
    data class Success(val matcher: (ByteArray) -> Boolean) : MlirResult()
    data class Failure(val message: String) : MlirResult()
}

fun compileMlir(src: String): MlirResult {
    return if ("compile_ok" in src) {
        val defaultPat = 0x1122334455667788L
        val pat = extractHexPattern(src, defaultPat)
        val matcher: (ByteArray) -> Boolean = { data ->
            if (data.size < 8) return@matcher false
            val word = ((data[0].toLong() and 0xFF) shl 56) or
                    ((data[1].toLong() and 0xFF) shl 48) or
                    ((data[2].toLong() and 0xFF) shl 40) or
                    ((data[3].toLong() and 0xFF) shl 32) or
                    ((data[4].toLong() and 0xFF) shl 24) or
                    ((data[5].toLong() and 0xFF) shl 16) or
                    ((data[6].toLong() and 0xFF) shl 8) or
                    (data[7].toLong() and 0xFF)
            word == pat
        }
        MlirResult.Success(matcher)
    } else {
        MlirResult.Failure("mock compile failure")
    }
}

fun interpretMlir(src: String, data: ByteArray): Boolean {
    if (data.size < 8) return false
    val defaultPat = 0x1122334455667788L
    val pat = extractHexPattern(src, defaultPat)

    for (i in 0 until data.size - 7) {
        val word = ((data[i].toLong() and 0xFF) shl 56) or
                ((data[i + 1].toLong() and 0xFF) shl 48) or
                ((data[i + 2].toLong() and 0xFF) shl 40) or
                ((data[i + 3].toLong() and 0xFF) shl 32) or
                ((data[i + 4].toLong() and 0xFF) shl 24) or
                ((data[i + 5].toLong() and 0xFF) shl 16) or
                ((data[i + 6].toLong() and 0xFF) shl 8) or
                (data[i + 7].toLong() and 0xFF)
        if (word == pat) return true
    }
    return false
}

private fun extractHexPattern(src: String, default: Long): Long {
    val idx = src.indexOf("0x")
    if (idx < 0) return default
    val snippet = src.substring(idx)
    var end = 2
    while (end < snippet.length && snippet[end].isDigit() ||
        (end < snippet.length && snippet[end] in 'a'..'f') ||
        (end < snippet.length && snippet[end] in 'A'..'F')
    ) {
        end++
    }
    val lit = snippet.substring(0, end)
    return lit.substring(2).toLongOrNull(16) ?: default
}
