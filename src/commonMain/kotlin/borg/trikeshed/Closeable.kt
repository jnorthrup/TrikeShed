package borg.trikeshed

/**
 * Common close-only resource contract.
 *
 * Keep this as an alias so JVM NIO typealiases can point at JDK types that
 * already implement AutoCloseable.
 */
typealias Closeable = AutoCloseable
