/*
 * Linux X64 (native) implementation of Jep466PlatformParser
 * Uses JNI to call java.lang.classfile or a native classfile parser.
 */
package org.xvm.activejs

import borg.trikeshed.parse.confix.SaxEvent

actual object Jep466PlatformParser {
    actual fun walkClassFile(bytes: ByteArray, action: (SaxEvent) -> Unit) {
        // Native target: use JNI to delegate to JVM classfile API
        // or use a native classfile parser (e.g., classfile-rs via C interop)
        // For now, emit minimal structure
        
        if (bytes.size < 8) return

        val magic = bytes[0].toInt() and 0xFF shl 24 |
                    bytes[1].toInt() and 0xFF shl 16 |
                    bytes[2].toInt() and 0xFF shl 8  |
                    bytes[3].toInt() and 0xFF

        if (magic != 0xCAFEBABE) return

        var offset = 0
        action(SaxEvent.Enter(0, offset++))
        action(SaxEvent.Leave(0, offset++))
    }
}