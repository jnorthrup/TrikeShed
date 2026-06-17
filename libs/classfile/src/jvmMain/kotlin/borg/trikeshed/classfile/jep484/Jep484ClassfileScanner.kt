package borg.trikeshed.classfile.jep484

import borg.trikeshed.classfile.model.BytecodePointcutKind
import borg.trikeshed.classfile.model.PointcutCoordinate
import borg.trikeshed.classfile.model.PointcutCoordinateSeries
import borg.trikeshed.classfile.model.SourceCoordinate
import borg.trikeshed.classfile.model.SymbolCoordinate
import borg.trikeshed.lib.toSeries
import java.lang.classfile.Attributes
import java.lang.classfile.ClassFile
import java.lang.classfile.CodeElement
import java.lang.classfile.Instruction
import java.lang.classfile.Opcode
import java.lang.classfile.attribute.LineNumberInfo
import java.lang.classfile.attribute.LocalVariableInfo
import java.lang.classfile.instruction.ArrayLoadInstruction
import java.lang.classfile.instruction.ArrayStoreInstruction
import java.lang.classfile.instruction.ConstantInstruction
import java.lang.classfile.instruction.ConvertInstruction
import java.lang.classfile.instruction.FieldInstruction
import java.lang.classfile.instruction.IncrementInstruction
import java.lang.classfile.instruction.InvokeInstruction
import java.lang.classfile.instruction.LoadInstruction
import java.lang.classfile.instruction.NewMultiArrayInstruction
import java.lang.classfile.instruction.NewObjectInstruction
import java.lang.classfile.instruction.NewPrimitiveArrayInstruction
import java.lang.classfile.instruction.NewReferenceArrayInstruction
import java.lang.classfile.instruction.OperatorInstruction
import java.lang.classfile.instruction.ReturnInstruction
import java.lang.classfile.instruction.StackInstruction
import java.lang.classfile.instruction.StoreInstruction
import java.lang.classfile.instruction.TypeCheckInstruction

class Jep484ClassfileScanner {
    fun scan(bytes: ByteArray, language: String = "jvm"): PointcutCoordinateSeries {
        val classModel = ClassFile.of().parse(bytes)
        val sourceFile = classModel.findAttribute(Attributes.sourceFile())
            .map { it.sourceFile().stringValue() }
            .orElse(classModel.thisClass().asInternalName().substringAfterLast('/') + ".class")
        val owner = classModel.thisClass().asInternalName()
        val coordinates = ArrayList<PointcutCoordinate>()

        classModel.methods().forEach { method ->
            val methodName = method.methodName().stringValue()
            val methodDescriptor = method.methodType().stringValue()
            val code = method.code().orElse(null) ?: return@forEach
            val lines = code.findAttribute(Attributes.lineNumberTable())
                .map { it.lineNumbers() }
                .orElse(emptyList())
            val locals = code.findAttribute(Attributes.localVariableTable())
                .map { it.localVariables() }
                .orElse(emptyList())

            var pc = 0
            code.forEach { element: CodeElement ->
                val instruction = element as? Instruction
                if (instruction != null) {
                    kindOf(instruction)?.let { kind ->
                        coordinates.add(
                            PointcutCoordinate(
                                kind = kind,
                                jvmOpcode = instruction.opcode().name,
                                bytecodeOffset = pc,
                                source = SourceCoordinate(
                                    sourceFile = sourceFile,
                                    line = lineFor(pc, lines),
                                    column = -1,
                                    language = language,
                                    bytecodeOffset = pc,
                                ),
                                symbol = symbolFor(
                                    owner = owner,
                                    methodName = methodName,
                                    methodDescriptor = methodDescriptor,
                                    instruction = instruction,
                                    pc = pc,
                                    locals = locals,
                                ),
                            ),
                        )
                    }
                    pc += instruction.sizeInBytes()
                }
            }
        }

        return coordinates.toSeries()
    }

    private fun kindOf(instruction: Instruction): BytecodePointcutKind? = when (instruction) {
        is FieldInstruction -> when (instruction.opcode()) {
            Opcode.GETFIELD -> BytecodePointcutKind.INSTANCE_FIELD_READ
            Opcode.PUTFIELD -> BytecodePointcutKind.INSTANCE_FIELD_WRITE
            Opcode.GETSTATIC -> BytecodePointcutKind.STATIC_FIELD_READ
            Opcode.PUTSTATIC -> BytecodePointcutKind.STATIC_FIELD_WRITE
            else -> null
        }
        is LoadInstruction -> BytecodePointcutKind.LOCAL_READ
        is StoreInstruction -> BytecodePointcutKind.LOCAL_WRITE
        is ArrayLoadInstruction -> BytecodePointcutKind.ARRAY_READ
        is ArrayStoreInstruction -> BytecodePointcutKind.ARRAY_WRITE
        is ConstantInstruction -> BytecodePointcutKind.CONSTANT
        is InvokeInstruction -> BytecodePointcutKind.INVOKE
        is OperatorInstruction -> BytecodePointcutKind.OPERATOR
        is IncrementInstruction -> BytecodePointcutKind.OPERATOR
        is ConvertInstruction -> BytecodePointcutKind.CONVERSION
        is ReturnInstruction -> BytecodePointcutKind.RETURN
        is TypeCheckInstruction -> BytecodePointcutKind.TYPE_CHECK
        is NewObjectInstruction -> BytecodePointcutKind.NEW_VALUE
        is NewPrimitiveArrayInstruction -> BytecodePointcutKind.NEW_VALUE
        is NewReferenceArrayInstruction -> BytecodePointcutKind.NEW_VALUE
        is NewMultiArrayInstruction -> BytecodePointcutKind.NEW_VALUE
        is StackInstruction -> BytecodePointcutKind.STACK
        else -> null
    }

    private fun lineFor(pc: Int, lines: List<LineNumberInfo>): Int {
        var current = -1
        var currentStart = Int.MIN_VALUE
        lines.forEach { line ->
            val start = line.startPc()
            if (start <= pc && start >= currentStart) {
                currentStart = start
                current = line.lineNumber()
            }
        }
        return current
    }

    private fun symbolFor(
        owner: String,
        methodName: String,
        methodDescriptor: String,
        instruction: Instruction,
        pc: Int,
        locals: List<LocalVariableInfo>,
    ): SymbolCoordinate = when (instruction) {
        is FieldInstruction -> SymbolCoordinate(
            owner = instruction.owner().asInternalName(),
            name = instruction.name().stringValue(),
            descriptor = instruction.type().stringValue(),
            methodName = methodName,
            methodDescriptor = methodDescriptor,
        )
        is InvokeInstruction -> SymbolCoordinate(
            owner = instruction.owner().asInternalName(),
            name = instruction.name().stringValue(),
            descriptor = instruction.type().stringValue(),
            methodName = methodName,
            methodDescriptor = methodDescriptor,
        )
        is LoadInstruction -> localSymbol(owner, methodName, methodDescriptor, instruction.slot(), pc, locals, instruction.typeKind().name)
        is StoreInstruction -> localSymbol(owner, methodName, methodDescriptor, instruction.slot(), pc, locals, instruction.typeKind().name)
        is IncrementInstruction -> localSymbol(owner, methodName, methodDescriptor, instruction.slot(), pc, locals, "IINC")
        is ConstantInstruction -> SymbolCoordinate(
            owner = owner,
            name = instruction.constantValue().toString(),
            descriptor = instruction.typeKind().name,
            methodName = methodName,
            methodDescriptor = methodDescriptor,
        )
        is ConvertInstruction -> SymbolCoordinate(
            owner = owner,
            name = instruction.opcode().name,
            descriptor = "${instruction.fromType().name}->${instruction.toType().name}",
            methodName = methodName,
            methodDescriptor = methodDescriptor,
        )
        is OperatorInstruction -> SymbolCoordinate(
            owner = owner,
            name = instruction.opcode().name,
            descriptor = instruction.typeKind().name,
            methodName = methodName,
            methodDescriptor = methodDescriptor,
        )
        is ArrayLoadInstruction -> SymbolCoordinate(owner, instruction.opcode().name, instruction.typeKind().name, methodName, methodDescriptor)
        is ArrayStoreInstruction -> SymbolCoordinate(owner, instruction.opcode().name, instruction.typeKind().name, methodName, methodDescriptor)
        is ReturnInstruction -> SymbolCoordinate(owner, instruction.opcode().name, instruction.typeKind().name, methodName, methodDescriptor)
        is TypeCheckInstruction -> SymbolCoordinate(owner, instruction.opcode().name, instruction.type().asInternalName(), methodName, methodDescriptor)
        is NewObjectInstruction -> SymbolCoordinate(owner, instruction.opcode().name, instruction.className().asInternalName(), methodName, methodDescriptor)
        is NewPrimitiveArrayInstruction -> SymbolCoordinate(owner, instruction.opcode().name, instruction.typeKind().name, methodName, methodDescriptor)
        is NewReferenceArrayInstruction -> SymbolCoordinate(owner, instruction.opcode().name, instruction.componentType().asInternalName(), methodName, methodDescriptor)
        is NewMultiArrayInstruction -> SymbolCoordinate(owner, instruction.opcode().name, instruction.arrayType().asInternalName(), methodName, methodDescriptor)
        else -> SymbolCoordinate(owner, instruction.opcode().name, instruction.opcode().kind().name, methodName, methodDescriptor)
    }

    private fun localSymbol(
        owner: String,
        methodName: String,
        methodDescriptor: String,
        slot: Int,
        pc: Int,
        locals: List<LocalVariableInfo>,
        fallbackDescriptor: String,
    ): SymbolCoordinate {
        val local = locals.firstOrNull { info ->
            info.slot() == slot && pc >= info.startPc() && pc < info.startPc() + info.length()
        }
        return SymbolCoordinate(
            owner = owner,
            name = local?.name()?.stringValue() ?: "slot$slot",
            descriptor = local?.type()?.stringValue() ?: fallbackDescriptor,
            methodName = methodName,
            methodDescriptor = methodDescriptor,
        )
    }
}
