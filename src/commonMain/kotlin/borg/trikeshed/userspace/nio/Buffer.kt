@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING", "RedundantVisibilityModifier", "unused")

package borg.trikeshed.userspace.nio

// Generated from Amazon Corretto JDK 25 java.base NIO public/protected API via javap.
// Declarations intentionally mirror JDK taxonomy and contain no implementations.
public abstract class Buffer {
    fun capacity(): Int
    fun position(): Int
    fun position(p0: Int): borg.trikeshed.userspace.nio.Buffer
    fun limit(): Int
    fun limit(p0: Int): borg.trikeshed.userspace.nio.Buffer
    fun mark(): borg.trikeshed.userspace.nio.Buffer
    fun reset(): borg.trikeshed.userspace.nio.Buffer
    fun clear(): borg.trikeshed.userspace.nio.Buffer
    fun flip(): borg.trikeshed.userspace.nio.Buffer
    fun rewind(): borg.trikeshed.userspace.nio.Buffer
    fun remaining(): Int
    fun hasRemaining(): Boolean
    fun isReadOnly(): Boolean
    fun hasArray(): Boolean
    fun array(): Any
    fun arrayOffset(): Int
    fun isDirect(): Boolean
    fun slice(): borg.trikeshed.userspace.nio.Buffer
    fun slice(p0: Int, p1: Int): borg.trikeshed.userspace.nio.Buffer
    fun duplicate(): borg.trikeshed.userspace.nio.Buffer
}
