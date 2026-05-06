@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING", "RedundantVisibilityModifier", "unused")

package borg.trikeshed.userspace.nio.channels

import borg.trikeshed.Closeable

// Generated from Amazon Corretto JDK 25 java.base NIO public/protected API via javap.
// Declarations intentionally mirror JDK taxonomy and contain no implementations.
//
// COMPATIBILITY SURFACE ONLY. The transport substrate is:
//   borg.trikeshed.userspace.Channel         — operation queue (submit/wait/peek)
//   borg.trikeshed.userspace.File            — handle lifecycle (open/close/isOpen)
//   borg.trikeshed.userspace.FunctionalUringFacade — SQE/CQE plumbing
//   borg.trikeshed.userspace.ByteRegion      — mutable read sink
//   borg.trikeshed.lib.ByteSeries            — immutable write source
//   borg.trikeshed.userspace.network.Channel — protocol session surface
//
// Do not route new IO through these stubs. Implement UserspaceChannelBackend instead.
public interface Channel : Closeable {
    fun isOpen(): Boolean = TODO("NIO common stub")
    override fun close(): Unit = TODO("NIO common stub")
}
