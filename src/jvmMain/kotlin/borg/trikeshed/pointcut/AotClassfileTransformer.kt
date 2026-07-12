package borg.trikeshed.pointcut

import java.io.File
import java.lang.constant.ClassDesc
import java.lang.constant.MethodTypeDesc

object AotClassfileTransformer {
    fun transformDirectory(dir: File) {
        // Implementation provided via Java ClassFile API interop wrapper since Kotlin
        // compiler has issues parsing Java 24/25 new java.lang.classfile.*
        val bytes = file.readBytes()
        val transformerClass = Class.forName("borg.trikeshed.pointcut.JavaAotClassfileTransformer")
        val transformMethod = transformerClass.getMethod("transform", ByteArray::class.java)
        val newBytes = transformMethod.invoke(null, bytes) as ByteArray
        file.writeBytes(newBytes)
    }
}
