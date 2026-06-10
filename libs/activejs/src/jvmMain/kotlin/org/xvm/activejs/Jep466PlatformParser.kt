/*
 * JVM implementation of Jep466PlatformParser
 * Delegates to Jep466Cursor which uses java.lang.classfile (JEP 466).
 */
package org.xvm.activejs

import org.xvm.cursor.Jep466Cursor
import borg.trikeshed.parse.confix.SaxEvent

actual object Jep466PlatformParser {
    actual fun walkClassFile(bytes: ByteArray, action: (SaxEvent) -> Unit) {
        Jep466Cursor.walkClassFile(bytes, action)
    }
}