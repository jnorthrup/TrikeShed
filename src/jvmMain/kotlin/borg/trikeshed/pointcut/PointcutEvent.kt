package borg.trikeshed.pointcut

import borg.trikeshed.cursor.FieldSynapse
import borg.trikeshed.cursor.TypedefProductionSystem

data class PointcutEvent(
    val vmFacet: VmFacet,
    val coordinate: String,
    val target: Any?,
    val propertyName: String,
    val newValue: Any?,
    val seq: Int = 0,
    val sourcePath: String? = null,
    val line: Int = -1,
    val column: Int = -1,
    val isRoot: Boolean = false
) {
    fun toFieldSynapse(): FieldSynapse {
        val methodIdx = TypedefProductionSystem.InternPool.intern(coordinate)
        val opcode = FieldSynapse.OP_L_GET.toByte()
        val cHash = TypedefProductionSystem.callsiteHash(opcode, methodIdx, 0)
        return FieldSynapse(
            phase = 0.toByte(),
            opcode = opcode,
            methodIdx = methodIdx,
            addr = 0,
            seq = seq,
            nano = 0L,
            callsiteHash = cHash,
            templateIdx = FieldSynapse.TPL_BEFORE_GET
        )
    }

    companion object {
        fun fromFieldSynapse(synapse: FieldSynapse): PointcutEvent {
            val coordinate = TypedefProductionSystem.InternPool.resolve(synapse.methodIdx)
            return PointcutEvent(
                vmFacet = VmFacet.GRAAL_PYTHON,
                coordinate = coordinate,
                target = null,
                propertyName = "",
                newValue = null,
                seq = synapse.seq
            )
        }
    }
}
