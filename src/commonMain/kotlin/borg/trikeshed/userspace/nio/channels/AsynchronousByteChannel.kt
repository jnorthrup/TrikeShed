@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING", "RedundantVisibilityModifier", "unused")

package borg.trikeshed.userspace.nio.channels

import borg.trikeshed.lib.ByteSeries
import borg.trikeshed.userspace.ByteRegion

// Generated from Amazon Corretto JDK 25 java.base NIO public/protected API via javap.
// Declarations intentionally mirror JDK taxonomy and contain no implementations.
public interface AsynchronousByteChannel : AsynchronousChannel {
    fun <A> read(dst: ByteRegion, attachment: A, handler: CompletionHandler<Int, in A>): Unit
    fun read(dst: ByteRegion): Int
    fun <A> write(src: ByteSeries, attachment: A, handler: CompletionHandler<Int, in A>): Unit
    fun write(src: ByteSeries): Int
}
