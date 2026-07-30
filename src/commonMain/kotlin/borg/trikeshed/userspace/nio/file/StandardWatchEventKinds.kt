@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING", "RedundantVisibilityModifier", "unused")

package borg.trikeshed.userspace.nio.file

// Generated from Amazon Corretto JDK 25 java.base NIO public/protected API via javap.
// Declarations intentionally mirror JDK taxonomy and contain no implementations.
public class StandardWatchEventKinds {
    private class StdWatchEventKind<T : Any>(private val _name: String, private val _type: kotlin.reflect.KClass<T>) : borg.trikeshed.userspace.nio.file.WatchEvent.Kind<T> {
        override fun name(): String = _name
        override fun type(): kotlin.reflect.KClass<T> = _type
    }

    companion object {
        val OVERFLOW: borg.trikeshed.userspace.nio.file.WatchEvent.Kind<Any> = StdWatchEventKind("OVERFLOW", Any::class)
        val ENTRY_CREATE: borg.trikeshed.userspace.nio.file.WatchEvent.Kind<borg.trikeshed.userspace.nio.file.Path> = StdWatchEventKind("ENTRY_CREATE", borg.trikeshed.userspace.nio.file.Path::class)
        val ENTRY_DELETE: borg.trikeshed.userspace.nio.file.WatchEvent.Kind<borg.trikeshed.userspace.nio.file.Path> = StdWatchEventKind("ENTRY_DELETE", borg.trikeshed.userspace.nio.file.Path::class)
        val ENTRY_MODIFY: borg.trikeshed.userspace.nio.file.WatchEvent.Kind<borg.trikeshed.userspace.nio.file.Path> = StdWatchEventKind("ENTRY_MODIFY", borg.trikeshed.userspace.nio.file.Path::class)
    }
}
