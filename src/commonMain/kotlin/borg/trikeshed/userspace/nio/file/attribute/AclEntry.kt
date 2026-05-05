@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING", "RedundantVisibilityModifier", "unused")

package borg.trikeshed.userspace.nio.file.attribute

// Generated from Amazon Corretto JDK 25 java.base NIO public/protected API via javap.
// Declarations intentionally mirror JDK taxonomy and contain no implementations.
expect class AclEntry {
    fun type(): borg.trikeshed.userspace.nio.file.attribute.AclEntryType
    fun principal(): borg.trikeshed.userspace.nio.file.attribute.UserPrincipal
    fun permissions(): java.util.Set<borg.trikeshed.userspace.nio.file.attribute.AclEntryPermission>
    fun flags(): java.util.Set<borg.trikeshed.userspace.nio.file.attribute.AclEntryFlag>
    override fun equals(p0: Any?): Boolean
    override fun hashCode(): Int
    override fun toString(): String
    companion object {
        fun newBuilder(): borg.trikeshed.userspace.nio.file.attribute.AclEntry.Builder
        fun newBuilder(p0: borg.trikeshed.userspace.nio.file.attribute.AclEntry): borg.trikeshed.userspace.nio.file.attribute.AclEntry.Builder
    }

    expect class Builder {
        fun build(): borg.trikeshed.userspace.nio.file.attribute.AclEntry
        fun setType(p0: borg.trikeshed.userspace.nio.file.attribute.AclEntryType): borg.trikeshed.userspace.nio.file.attribute.AclEntry.Builder
        fun setPrincipal(p0: borg.trikeshed.userspace.nio.file.attribute.UserPrincipal): borg.trikeshed.userspace.nio.file.attribute.AclEntry.Builder
        fun setPermissions(p0: java.util.Set<borg.trikeshed.userspace.nio.file.attribute.AclEntryPermission>): borg.trikeshed.userspace.nio.file.attribute.AclEntry.Builder
        fun setPermissions(vararg p0: borg.trikeshed.userspace.nio.file.attribute.AclEntryPermission): borg.trikeshed.userspace.nio.file.attribute.AclEntry.Builder
        fun setFlags(p0: java.util.Set<borg.trikeshed.userspace.nio.file.attribute.AclEntryFlag>): borg.trikeshed.userspace.nio.file.attribute.AclEntry.Builder
        fun setFlags(vararg p0: borg.trikeshed.userspace.nio.file.attribute.AclEntryFlag): borg.trikeshed.userspace.nio.file.attribute.AclEntry.Builder
    }
}
