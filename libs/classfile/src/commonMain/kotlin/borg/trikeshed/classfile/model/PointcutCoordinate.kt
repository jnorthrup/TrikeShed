package borg.trikeshed.classfile.model

import borg.trikeshed.lib.Series
import borg.trikeshed.lib.j
import borg.trikeshed.lib.toSeries
import borg.trikeshed.lib.view

/** Value-bearing JVM bytecode sites that can become pointcut coordinates. */
enum class BytecodePointcutKind {
    INSTANCE_FIELD_READ,
    INSTANCE_FIELD_WRITE,
    STATIC_FIELD_READ,
    STATIC_FIELD_WRITE,
    LOCAL_READ,
    LOCAL_WRITE,
    ARRAY_READ,
    ARRAY_WRITE,
    CONSTANT,
    INVOKE,
    OPERATOR,
    CONVERSION,
    COMPARISON,
    BRANCH,
    RETURN,
    TYPE_CHECK,
    NEW_VALUE,
    STACK,
}

data class SourceCoordinate(
    val sourceFile: String,
    val line: Int,
    val column: Int,
    val language: String,
    val bytecodeOffset: Int,
)

data class SymbolCoordinate(
    val owner: String,
    val name: String,
    val descriptor: String,
    val methodName: String,
    val methodDescriptor: String,
)

data class PointcutCoordinate(
    val kind: BytecodePointcutKind,
    val jvmOpcode: String,
    val bytecodeOffset: Int,
    val source: SourceCoordinate,
    val symbol: SymbolCoordinate,
)

typealias PointcutCoordinateSeries = Series<PointcutCoordinate>

/**
 * `/` is the value-side reduction gateway: keep coordinates of a kind as a Series.
 * This keeps downstream `α` transforms lazy and uses `.view` only at stdlib boundaries.
 */
operator fun PointcutCoordinateSeries.div(kind: BytecodePointcutKind): PointcutCoordinateSeries {
    val kept = view.filter { it.kind == kind }.toList()
    return kept.toSeries()
}

/** `%` is the index-side reduction gateway: select matching coordinate indexes as a Series. */
operator fun PointcutCoordinateSeries.rem(kind: BytecodePointcutKind): Series<Int> {
    val indexes = ArrayList<Int>()
    for (i in 0 until a) {
        if (b(i).kind == kind) indexes.add(i)
    }
    return indexes.toSeries()
}

fun emptyPointcutCoordinates(): PointcutCoordinateSeries = 0 j { _: Int ->
    throw IndexOutOfBoundsException("empty pointcut coordinate series")
}
