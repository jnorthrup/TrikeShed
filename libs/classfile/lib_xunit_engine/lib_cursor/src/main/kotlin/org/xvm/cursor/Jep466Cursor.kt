package org.xvm.cursor

import borg.trikeshed.cursor.IOMemento
import borg.trikeshed.parse.confix.SaxEvent
import java.lang.classfile.ClassFile
import java.lang.classfile.ClassModel
import java.lang.classfile.FieldModel
import java.lang.classfile.MethodModel

/**
 * Maps the standard JEP 466 (ClassFile API) onto Confix SAX/JAX without
 * Matryoshka aliases or custom DomainCursor wrappers.
 */
object Jep466Cursor {

    /**
     * Parses a raw byte array representing a ClassFile and emits Confix SAX Events.
     */
    fun walkClassFile(bytes: ByteArray, action: (SaxEvent) -> Unit) {
        val model: ClassModel = ClassFile.of().parse(bytes)
        
        var offset = 0
        
        // Enter Class (IoObject)
        action(SaxEvent.Enter(IOMemento.IoObject, offset++))
        
        // Emitting fields as a nested IoArray
        action(SaxEvent.Enter(IOMemento.IoArray, offset++))
        model.fields().forEach { field: FieldModel ->
            action(SaxEvent.Enter(IOMemento.IoString, offset++))
            // (In a real JAX DOM we'd emit the string value, here we just emit offsets)
            action(SaxEvent.Leave(IOMemento.IoString, offset++))
        }
        action(SaxEvent.Leave(IOMemento.IoArray, offset++))
        
        // Emitting methods as a nested IoArray
        action(SaxEvent.Enter(IOMemento.IoArray, offset++))
        model.methods().forEach { method: MethodModel ->
            action(SaxEvent.Enter(IOMemento.IoString, offset++))
            action(SaxEvent.Leave(IOMemento.IoString, offset++))
        }
        action(SaxEvent.Leave(IOMemento.IoArray, offset++))
        
        // Leave Class
        action(SaxEvent.Leave(IOMemento.IoObject, offset++))
    }
}