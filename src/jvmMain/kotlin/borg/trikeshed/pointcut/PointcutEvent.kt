package borg.trikeshed.pointcut

data class PointcutEvent(
    val vmFacet: VmFacet,
    val coordinate: String,
    val target: Any?,
    val propertyName: String,
    val newValue: Any?,
    val timestamp: Long = System.currentTimeMillis()
)
