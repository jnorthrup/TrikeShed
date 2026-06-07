package borg.trikeshed.ffi.nio

import kotlinx.cinterop.*
import platform.posix.memcpy

class NativeMlxNioWireProto : MlxNioWireProto {
    override suspend fun processMlxBuffer(buffer: ByteArray): ByteArray {
        val outBuffer = ByteArray(buffer.size)

        // Simulating FFI memory copy per CCEK constraints avoiding byte-by-byte copies
        buffer.usePinned { pinnedIn ->
            outBuffer.usePinned { pinnedOut ->
                memcpy(pinnedOut.addressOf(0), pinnedIn.addressOf(0), buffer.size.convert())
            }
        }

        return outBuffer
    }
}
