package org.xvm.cursor

import borg.trikeshed.parse.confix.ConfixRole
import borg.trikeshed.parse.confix.BlackBoardEntry
import borg.trikeshed.parse.confix.confixDoc
import java.lang.classfile.ClassFile

object Jep483AotCursor {
    fun parseBlackboard(dumpText: String): List<BlackBoardEntry> {
        val entries = mutableListOf<BlackBoardEntry>()
        val lines = dumpText.split("\n", "\r\n")
        val classFileContext = ClassFile.of()

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
            
            // Expected Format: java/lang/Object id: 0
            val parts = trimmed.split(" ")
            val className = parts[0]
            
            // Resolve class resource to extract real features (JEP 483 / 466 integration)
            val resourcePath = className + ".class"
            val inputStream = ClassLoader.getSystemResourceAsStream(resourcePath)
            
            if (inputStream != null) {
                val bytes = inputStream.readAllBytes()
                val classModel = classFileContext.parse(bytes)
                
                val features = mutableListOf<String>()
                classModel.fields().forEach { f -> 
                    features.add("field: ${f.fieldName().stringValue()}") 
                }
                classModel.methods().forEach { m -> 
                    features.add("method: ${m.methodName().stringValue()}") 
                }
                
                // YAML representation for Confix
                val docStr = """
                    class: $className
                    features: [${features.joinToString(", ")}]
                """.trimIndent()
                
                entries.add(
                    BlackBoardEntry(
                        doc = confixDoc(docStr),
                        role = ConfixRole.OBSERVATION,
                        provenance = "JEP483_AOT_DUMP"
                    )
                )
            } else {
                entries.add(
                    BlackBoardEntry(
                        doc = confixDoc("class: $className\nfeatures: []"),
                        role = ConfixRole.OBSERVATION,
                        provenance = "JEP483_AOT_DUMP"
                    )
                )
            }
        }
        return entries
    }
}
