package borg.literbike.betanet

/**
 * Detection pipeline: SIMD anchors -> MLIR compiled -> MLIR interpreted fallback.
 * Ported from literbike/src/betanet/detector_pipeline.rs.
 */

/**
 * Detection result from the pipeline.
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
            compileMlir(src).fold(
                onSuccess = { compiled ->
                    if (compiled.run(data)) {
                        return Detection.MlirCompiled
                    }
                },
                onFailure = {
                    // Fall back to interpreter
                    if (interpretMlir(src, data)) {
                        return Detection.MlirInterpreted
                    }
                }
            )
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
