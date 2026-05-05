@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING", "RedundantVisibilityModifier", "unused")

package borg.trikeshed.userspace.nio.channels

// Generated from Amazon Corretto JDK 25 java.base NIO public/protected API via javap.
// Declarations intentionally mirror JDK taxonomy and contain no implementations.
public abstract class Pipe {
    protected constructor()
    fun source(): borg.trikeshed.userspace.nio.channels.Pipe.SourceChannel
    fun sink(): borg.trikeshed.userspace.nio.channels.Pipe.SinkChannel
    companion object {
        fun `open`(): borg.trikeshed.userspace.nio.channels.Pipe
    }

    public abstract class SinkChannel : borg.trikeshed.userspace.nio.channels.spi.AbstractSelectableChannel, borg.trikeshed.userspace.nio.channels.WritableByteChannel, borg.trikeshed.userspace.nio.channels.GatheringByteChannel {
        protected constructor(p0: borg.trikeshed.userspace.nio.channels.spi.SelectorProvider)
        fun validOps(): Int
    }

    public abstract class SourceChannel : borg.trikeshed.userspace.nio.channels.spi.AbstractSelectableChannel, borg.trikeshed.userspace.nio.channels.ReadableByteChannel, borg.trikeshed.userspace.nio.channels.ScatteringByteChannel {
        protected constructor(p0: borg.trikeshed.userspace.nio.channels.spi.SelectorProvider)
        fun validOps(): Int
    }
}
