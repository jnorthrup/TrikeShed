package borg.trikeshed.pointcut.coord

import borg.trikeshed.classfile.model.BytecodePointcutKind

object PointcutKinds {
    fun kindOf(opcode: Byte): BytecodePointcutKind {
        val unsigned = opcode.toInt() and 0xFF
        return when (unsigned) {
            0xA5 -> BytecodePointcutKind.LOCAL_READ
            0xA6 -> BytecodePointcutKind.LOCAL_WRITE
            0xA7 -> BytecodePointcutKind.INSTANCE_FIELD_READ
            0xA8 -> BytecodePointcutKind.INSTANCE_FIELD_WRITE
            0x10 -> BytecodePointcutKind.INVOKE
            0x11 -> BytecodePointcutKind.NEW_VALUE
            0x12 -> BytecodePointcutKind.RETURN
            0x13 -> BytecodePointcutKind.INSTANCE_FIELD_READ
            0x14 -> BytecodePointcutKind.LOCAL_READ
            0x15 -> BytecodePointcutKind.CONVERSION
            else -> BytecodePointcutKind.CONSTANT
        }
    }
}
