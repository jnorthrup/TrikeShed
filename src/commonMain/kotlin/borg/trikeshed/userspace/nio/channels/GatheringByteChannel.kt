@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING", "RedundantVisibilityModifier", "unused")

package borg.trikeshed.userspace.nio.channels

import borg.trikeshed.userspace.nio.ByteBuffer

// Generated from Amazon Corretto JDK 25 java.base NIO public/protected API via javap.
// Declarations intentionally mirror JDK taxonomy and contain no implementations.
public interface GatheringByteChannel : WritableByteChannel {
    fun write(srcs: Array<out ByteBuffer>, offset: Int, length: Int): Long
    fun write(srcs: Array<out ByteBuffer>): Long
}
