package borg.trikeshed.pointcut;

import jdk.internal.classfile.ClassFile;
import jdk.internal.classfile.ClassModel;
import jdk.internal.classfile.ClassTransform;
import jdk.internal.classfile.CodeBuilder;
import jdk.internal.classfile.CodeElement;
import jdk.internal.classfile.instruction.FieldInstruction;
import jdk.internal.classfile.Opcode;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;

public class JavaAotClassfileTransformer {
    public static byte[] transform(byte[] bytes) {
        ClassModel classModel = ClassFile.of().parse(bytes);

        ClassTransform transform = ClassTransform.transformingMethodBodies((CodeBuilder builder, CodeElement element) -> {
            builder.with(element);

            if (element instanceof FieldInstruction fieldInst) {
                if (fieldInst.opcode() == Opcode.PUTFIELD || fieldInst.opcode() == Opcode.PUTSTATIC) {
                    String className = classModel.thisClass().asInternalName();
                    String fieldName = fieldInst.name().stringValue();
                    String coordinate = className + "." + fieldName;

                    builder.ldc("java");
                    builder.ldc(coordinate);
                    builder.aconst_null();
                    builder.ldc(fieldName);
                    builder.aconst_null();

                    MethodTypeDesc reportDesc = MethodTypeDesc.ofDescriptor("(Ljava/lang/String;Ljava/lang/String;Ljava/lang/Object;Ljava/lang/String;Ljava/lang/Object;)V");
                    ClassDesc reporterClass = ClassDesc.of("borg.trikeshed.pointcut.PointcutReporter");
                    builder.invokestatic(reporterClass, "report", reportDesc);
                }
            }
        });

        return ClassFile.of().transform(classModel, transform);
    }
}
