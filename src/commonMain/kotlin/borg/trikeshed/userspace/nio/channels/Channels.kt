@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING", "RedundantVisibilityModifier", "unused")

package borg.trikeshed.userspace.nio.channels

import borg.trikeshed.userspace.nio.charset.Charset
import borg.trikeshed.userspace.nio.charset.CharsetDecoder
import borg.trikeshed.userspace.nio.charset.CharsetEncoder

// Generated from Amazon Corretto JDK 25 java.base NIO public/protected API via javap.
// Coerced to TrikeShed types — InputStream/OutputStream return types simplified to Any.
public class Channels {
    companion object {
        fun newInputStreamForReadable(channel: ReadableByteChannel): Any = throw UnsupportedOperationException("Channels operations are not supported in commonMain")
        fun newOutputStreamForWritable(channel: WritableByteChannel): Any = throw UnsupportedOperationException("Channels operations are not supported in commonMain")
        fun newInputStreamForAsync(channel: AsynchronousByteChannel): Any = throw UnsupportedOperationException("Channels operations are not supported in commonMain")
        fun newOutputStreamForAsync(channel: AsynchronousByteChannel): Any = throw UnsupportedOperationException("Channels operations are not supported in commonMain")
        fun newReadableChannel(input: Any): ReadableByteChannel = throw UnsupportedOperationException("Channels operations are not supported in commonMain")
        fun newWritableChannel(output: Any): WritableByteChannel = throw UnsupportedOperationException("Channels operations are not supported in commonMain")
        fun newReader(channel: ReadableByteChannel, decoder: CharsetDecoder, minBufferCap: Int): Any = throw UnsupportedOperationException("Channels operations are not supported in commonMain")
        fun newReaderForCharset(channel: ReadableByteChannel, charsetName: String): Any = throw UnsupportedOperationException("Channels operations are not supported in commonMain")
        fun newReaderForDecoder(channel: ReadableByteChannel, charset: Charset): Any = throw UnsupportedOperationException("Channels operations are not supported in commonMain")
        fun newWriter(channel: WritableByteChannel, encoder: CharsetEncoder, minBufferCap: Int): Any = throw UnsupportedOperationException("Channels operations are not supported in commonMain")
        fun newWriterForCharset(channel: WritableByteChannel, charsetName: String): Any = throw UnsupportedOperationException("Channels operations are not supported in commonMain")
        fun newWriterForEncoder(channel: WritableByteChannel, charset: Charset): Any = throw UnsupportedOperationException("Channels operations are not supported in commonMain")
    }
}
