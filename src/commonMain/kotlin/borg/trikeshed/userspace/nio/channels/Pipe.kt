@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING", "RedundantVisibilityModifier", "unused")

package borg.trikeshed.userspace.nio.channels

import borg.trikeshed.userspace.nio.channels.spi.AbstractSelectableChannel
import borg.trikeshed.userspace.nio.channels.spi.SelectorProvider
// Generated from Amazon Corretto JDK 25 java.base NIO public/protected API via javap.
// Declarations intentionally mirror JDK taxonomy and contain no implementations.
public abstract class Pipe {
    protected constructor()
    abstract fun source(): Pipe.SourceChannel
    abstract fun sink(): Pipe.SinkChannel

    companion object {
        fun `open`(): Pipe = SelectorProvider.provider().openPipe()
    }

    public abstract class SinkChannel : AbstractSelectableChannel, WritableByteChannel, GatheringByteChannel {
        protected constructor(provider: SelectorProvider) : super(provider)
        public abstract override fun validOps(): Int
    }

    public abstract class SourceChannel : AbstractSelectableChannel, ReadableByteChannel, ScatteringByteChannel {
        protected constructor(provider: SelectorProvider) : super(provider)
        public abstract override fun validOps(): Int
    }
}
