@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING", "RedundantVisibilityModifier", "unused")

package borg.trikeshed.userspace.nio.channels

import borg.trikeshed.userspace.nio.charset.Charset
import borg.trikeshed.userspace.nio.charset.CharsetDecoder
import borg.trikeshed.userspace.nio.charset.CharsetEncoder

// Generated from Amazon Corretto JDK 25 java.base NIO public/protected API via javap.
// Coerced to TrikeShed types — InputStream/OutputStream return types simplified to Any.
public class Channels {
    companion object {
        fun newInputStreamForReadable(channel: ReadableByteChannel): Any = TODO("NIO common stub")
        fun newOutputStreamForWritable(channel: WritableByteChannel): Any = TODO("NIO common stub")
        fun newInputStreamForAsync(channel: AsynchronousByteChannel): Any = TODO("NIO common stub")
        fun newOutputStreamForAsync(channel: AsynchronousByteChannel): Any = TODO("NIO common stub")
        fun newReadableChannel(input: Any): ReadableByteChannel = TODO("NIO common stub")
        fun newWritableChannel(output: Any): WritableByteChannel = TODO("NIO common stub")
        fun newReader(channel: ReadableByteChannel, decoder: CharsetDecoder, minBufferCap: Int): Any = TODO("NIO common stub")
        fun newReaderForCharset(channel: ReadableByteChannel, charsetName: String): Any = TODO("NIO common stub")
        fun newReaderForDecoder(channel: ReadableByteChannel, charset: Charset): Any = TODO("NIO common stub")
        fun newWriter(channel: WritableByteChannel, encoder: CharsetEncoder, minBufferCap: Int): Any = TODO("NIO common stub")
        fun newWriterForCharset(channel: WritableByteChannel, charsetName: String): Any = TODO("NIO common stub")
        fun newWriterForEncoder(channel: WritableByteChannel, charset: Charset): Any = TODO("NIO common stub")
    }
}
