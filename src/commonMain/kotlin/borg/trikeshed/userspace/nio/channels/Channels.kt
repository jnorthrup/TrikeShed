@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING", "RedundantVisibilityModifier", "unused")

package borg.trikeshed.userspace.nio.channels

// Generated from Amazon Corretto JDK 25 java.base NIO public/protected API via javap.
// Declarations intentionally mirror JDK taxonomy and contain no implementations.
public class Channels {
    companion object {
        fun newInputStream(p0: borg.trikeshed.userspace.nio.channels.ReadableByteChannel): java.io.InputStream
        fun newOutputStream(p0: borg.trikeshed.userspace.nio.channels.WritableByteChannel): java.io.OutputStream
        fun newInputStream(p0: borg.trikeshed.userspace.nio.channels.AsynchronousByteChannel): java.io.InputStream
        fun newOutputStream(p0: borg.trikeshed.userspace.nio.channels.AsynchronousByteChannel): java.io.OutputStream
        fun newChannel(p0: java.io.InputStream): borg.trikeshed.userspace.nio.channels.ReadableByteChannel
        fun newChannel(p0: java.io.OutputStream): borg.trikeshed.userspace.nio.channels.WritableByteChannel
        fun newReader(p0: borg.trikeshed.userspace.nio.channels.ReadableByteChannel, p1: borg.trikeshed.userspace.nio.charset.CharsetDecoder, p2: Int): java.io.Reader
        fun newReader(p0: borg.trikeshed.userspace.nio.channels.ReadableByteChannel, p1: String): java.io.Reader
        fun newReader(p0: borg.trikeshed.userspace.nio.channels.ReadableByteChannel, p1: borg.trikeshed.userspace.nio.charset.Charset): java.io.Reader
        fun newWriter(p0: borg.trikeshed.userspace.nio.channels.WritableByteChannel, p1: borg.trikeshed.userspace.nio.charset.CharsetEncoder, p2: Int): java.io.Writer
        fun newWriter(p0: borg.trikeshed.userspace.nio.channels.WritableByteChannel, p1: String): java.io.Writer
        fun newWriter(p0: borg.trikeshed.userspace.nio.channels.WritableByteChannel, p1: borg.trikeshed.userspace.nio.charset.Charset): java.io.Writer
    }
}
