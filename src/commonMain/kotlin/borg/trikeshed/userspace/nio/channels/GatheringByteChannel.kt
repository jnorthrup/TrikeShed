@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING", "RedundantVisibilityModifier", "unused")

package borg.trikeshed.userspace.nio.channels

// Generated from Amazon Corretto JDK 25 java.base NIO public/protected API via javap.
// Declarations intentionally mirror JDK taxonomy and contain no implementations.
expect interface GatheringByteChannel : borg.trikeshed.userspace.nio.channels.WritableByteChannel {
    fun write(p0: Array<borg.trikeshed.userspace.nio.ByteBuffer>, p1: Int, p2: Int): Long
    fun write(p0: Array<borg.trikeshed.userspace.nio.ByteBuffer>): Long
}
