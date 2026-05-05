@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING", "RedundantVisibilityModifier", "unused")

package borg.trikeshed.userspace.nio.file

// Generated from Amazon Corretto JDK 25 java.base NIO public/protected API via javap.
// Declarations intentionally mirror JDK taxonomy and contain no implementations.
public interface Watchable {
    fun register(p0: borg.trikeshed.userspace.nio.file.WatchService, p1: Array<borg.trikeshed.userspace.nio.file.WatchEvent.Kind<*>>, vararg p2: borg.trikeshed.userspace.nio.file.WatchEvent.Modifier): borg.trikeshed.userspace.nio.file.WatchKey
    fun register(p0: borg.trikeshed.userspace.nio.file.WatchService, vararg p1: borg.trikeshed.userspace.nio.file.WatchEvent.Kind<*>): borg.trikeshed.userspace.nio.file.WatchKey
}
