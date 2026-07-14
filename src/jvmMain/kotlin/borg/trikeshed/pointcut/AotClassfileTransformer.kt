package borg.trikeshed.pointcut

import java.io.File
import java.lang.constant.ClassDesc
import java.lang.constant.MethodTypeDesc

object AotClassfileTransformer {
    fun transformDirectory(dir: java.io.File) {
        val transformerClass = Class.forName("borg.trikeshed.pointcut.JavaAotClassfileTransformer")
        val transformMethod = transformerClass.getMethod("transform", ByteArray::class.java)
        dir.walkTopDown().filter { it.isFile && it.extension == "class" }.forEach { classFile ->
            val bytes = classFile.readBytes()
            val newBytes = transformMethod.invoke(null, bytes) as ByteArray
            classFile.writeBytes(newBytes)
        }
    }
}
