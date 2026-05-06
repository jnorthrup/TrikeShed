@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING", "RedundantVisibilityModifier", "unused")

package borg.trikeshed.userspace.nio.channels

import borg.trikeshed.userspace.ByteRegion

// Generated from Amazon Corretto JDK 25 java.base NIO public/protected API via javap.
// Declarations intentionally mirror JDK taxonomy and contain no implementations.
public interface ScatteringByteChannel : ReadableByteChannel {
    fun read(dsts: Array<out ByteRegion>, offset: Int, length: Int): Long = TODO("NIO common stub")
    fun read(dsts: Array<out ByteRegion>): Long = TODO("NIO common stub")
}
