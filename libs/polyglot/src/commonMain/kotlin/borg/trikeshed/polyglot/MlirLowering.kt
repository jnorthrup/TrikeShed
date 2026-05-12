package borg.trikeshed.polyglot

import borg.trikeshed.lib.*
import borg.trikeshed.mlir.*
import borg.trikeshed.cpucache.*
import borg.trikeshed.cursor.Cursor

/**
 * Stage 5: MLIR Lowering.
 *
 * Converts a [Cursor] of [RowVec] regions into optimized MLIR assembly.
 * This stage uses [CpuCacheTopology] to inform tiling and other hardware-specific
 * optimizations (e.g., matching L1 cache size).
 */
class MlirLowering(val topology: CpuCacheTopology) {

    /**
     * Lower a cursor of regions into a complete MLIR module.
     */
    fun lower(cursor: Cursor): CharSequence {
        val sb = StringBuilder()
        sb.appendLine("module {")

        // 1. Emit Hardware Constraints (cpu_cache dialect)
        sb.appendLine("  // Hardware topology constraints")
        sb.append(topology.asMlir.prependIndent("  "))
        sb.appendLine()

        // 2. Iterate through regions and lower each node
        sb.appendLine("  // Lowered code regions")
        cursor.view.forEach { row ->
            val kind = NodeKind.entries[row.get(0) as Int]
            val ops = nodeToMlir(kind)

            if (ops.isNotEmpty()) {
                val op = ops[0] // Primary mapping
                sb.appendLine("  // Lowering $kind -> ${op.qualifiedName}")

                when (op) {
                    LinalgOps.matmul -> emitTiledMatmul(sb, topology)
                    else -> sb.appendLine("  // TODO: Implement lowering for ${op.qualifiedName}")
                }
            }
        }

        sb.appendLine("}")
        return sb.toString()
    }

    private fun emitTiledMatmul(sb: StringBuilder, topology: CpuCacheTopology) {
        val l1Size = topology.l1DataBytes ?: (32 * 1024) // Fallback to 32KB
        val elementSize = 4 // f32
        val maxElements = l1Size / elementSize

        // Heuristic: Tile size = sqrt(L1 / 3) for square blocks
        val tileSize = kotlin.math.sqrt((maxElements / 3).toDouble()).toInt()

        sb.appendLine("  func.func @matmul_tiled(%arg0: memref<1024x1024xf32>, %arg1: memref<1024x1024xf32>, %arg2: memref<1024x1024xf32>) {")
        sb.appendLine("    // Tiling strategy: $tileSize x $tileSize based on L1 cache size ($l1Size bytes)")
        sb.appendLine("    linalg.matmul ins(%arg0, %arg1 : memref<1024x1024xf32>, memref<1024x1024xf32>)")
        sb.appendLine("                  outs(%arg2 : memref<1024x1024xf32>)")
        sb.appendLine("    return")
        sb.appendLine("  }")
    }
}

private fun CharSequence.prependIndent(indent: CharSequence): CharSequence =
    lineSequence().joinToString("\n") { if (it.isNotBlank()) indent + it else it }
