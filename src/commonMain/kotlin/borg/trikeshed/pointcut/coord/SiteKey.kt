package borg.trikeshed.pointcut.coord

data class SiteKey(
    val owner: String,
    val methodName: String,
    val methodDescriptor: String,
    val bytecodeOffset: Int,
    val line: Int,
    val column: Int
)

val SiteKey.confixPath: String get() = if (bytecodeOffset < 0) {
    "guest/$owner/$methodName#$line:$column"
} else {
    "/classes/$owner/$methodName/$methodDescriptor/$bytecodeOffset"
}

fun borg.trikeshed.classfile.model.PointcutCoordinate.siteKey(): SiteKey = SiteKey(
    owner = symbol.owner,
    methodName = symbol.methodName,
    methodDescriptor = symbol.methodDescriptor,
    bytecodeOffset = bytecodeOffset,
    line = source.line,
    column = source.column
)

fun borg.trikeshed.dag.DagCoordinate.siteKey(line: Int = -1, column: Int = -1): SiteKey = SiteKey(
    owner = className,
    methodName = methodName,
    methodDescriptor = "",
    bytecodeOffset = bytecodeOffset,
    line = line,
    column = column
)
