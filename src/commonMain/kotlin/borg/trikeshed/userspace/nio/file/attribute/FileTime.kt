@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING", "RedundantVisibilityModifier", "unused")

package borg.trikeshed.userspace.nio.file.attribute

// Generated from Amazon Corretto JDK 25 java.base NIO public/protected API via javap.
// Declarations intentionally mirror JDK taxonomy and contain no implementations.
expect class FileTime {
    fun to(p0: java.util.concurrent.TimeUnit): Long
    fun toMillis(): Long
    fun toInstant(): java.time.Instant
    override fun equals(p0: Any?): Boolean
    override fun hashCode(): Int
    fun compareTo(p0: borg.trikeshed.userspace.nio.file.attribute.FileTime): Int
    override fun toString(): String
    fun compareTo(p0: Any): Int
    companion object {
        fun from(p0: Long, p1: java.util.concurrent.TimeUnit): borg.trikeshed.userspace.nio.file.attribute.FileTime
        fun fromMillis(p0: Long): borg.trikeshed.userspace.nio.file.attribute.FileTime
        fun from(p0: java.time.Instant): borg.trikeshed.userspace.nio.file.attribute.FileTime
    }
}
