package borg.trikeshed.runtime

import borg.trikeshed.classfile.model.PointcutCoordinate

/**
 * ConfixClassfileDir — classfile hierarchy as Confix paths.
 *
 * Projection-based: no node classes — just paths + facets + lazy Series.
 * Uses canonical [borg.trikeshed.lib.Series] — no local shadow of Join/Series.
 */
object ConfixClassfileDir {

    /** Root path prefix */
    const val ROOT = "/classes"

    /** Build canonical path from PointcutCoordinate */
    fun pathOf(pc: PointcutCoordinate): String =
        "$ROOT/${pc.symbol.owner}/${pc.symbol.methodName}/${pc.kind.name}/${pc.bytecodeOffset}"

    /** Node value as Confix-compatible JSON Map */
    fun nodeVal(pc: PointcutCoordinate): Map<String, Any> = mapOf(
        "kind" to pc.kind.name,
        "sourceFile" to pc.source.sourceFile,
        "line" to pc.source.line,
        "column" to pc.source.column,
        "language" to pc.source.language,
        "bytecodeOffset" to pc.source.bytecodeOffset,
        "owner" to pc.symbol.owner,
        "name" to pc.symbol.name,
        "descriptor" to pc.symbol.descriptor,
        "methodName" to pc.symbol.methodName,
        "methodDescriptor" to pc.symbol.methodDescriptor,
        "jvmOpcode" to pc.jvmOpcode,
        "facet" to 1L,
    )
}

// Note: the LCNC/Slab-facet helpers (withFacet, inMode, tagged, ChildRowVec,
// childRowVec) used to live below. They depended entirely on `SlabFacet` /
// `LCNCModeFacet`, both of which live in the now-excluded **classfile/slab/**
// package — an entire layer of TODO() stubs with no non-test consumers. The
// helpers have been removed; the real entry points above (pathOf, nodeVal)
// remain. Consumers of those helpers should switch to canonical Series
// projections (`series.size j { ... }`, `series α { ... }`).
