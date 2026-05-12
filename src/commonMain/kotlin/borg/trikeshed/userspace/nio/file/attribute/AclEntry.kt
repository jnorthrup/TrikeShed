@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING", "RedundantVisibilityModifier", "unused")

package borg.trikeshed.userspace.nio.file.attribute

// Generated from Amazon Corretto JDK 25 java.base NIO public/protected API via javap.
// Declarations intentionally mirror JDK taxonomy and contain no implementations.
public class AclEntry {
    fun type(): borg.trikeshed.userspace.nio.file.attribute.AclEntryType = TODO("NIO common stub")
    fun principal(): borg.trikeshed.userspace.nio.file.attribute.UserPrincipal = TODO("NIO common stub")
    fun permissions(): Set<borg.trikeshed.userspace.nio.file.attribute.AclEntryPermission> = TODO("NIO common stub")
    fun flags(): Set<borg.trikeshed.userspace.nio.file.attribute.AclEntryFlag> = TODO("NIO common stub")
    override fun equals(p0: Any?): Boolean = TODO("NIO common stub")
    override fun hashCode(): Int = TODO("NIO common stub")
    override fun toString():CharSequence= TODO("NIO common stub")
    companion object {
        fun newBuilder(): borg.trikeshed.userspace.nio.file.attribute.AclEntry.Builder = TODO("NIO common stub")
        fun newBuilder(p0: borg.trikeshed.userspace.nio.file.attribute.AclEntry): borg.trikeshed.userspace.nio.file.attribute.AclEntry.Builder = TODO("NIO common stub")
    }

    public class Builder {
        fun build(): borg.trikeshed.userspace.nio.file.attribute.AclEntry = TODO("NIO common stub")
        fun setType(p0: borg.trikeshed.userspace.nio.file.attribute.AclEntryType): borg.trikeshed.userspace.nio.file.attribute.AclEntry.Builder = TODO("NIO common stub")
        fun setPrincipal(p0: borg.trikeshed.userspace.nio.file.attribute.UserPrincipal): borg.trikeshed.userspace.nio.file.attribute.AclEntry.Builder = TODO("NIO common stub")
        fun setPermissions(p0: Set<borg.trikeshed.userspace.nio.file.attribute.AclEntryPermission>): borg.trikeshed.userspace.nio.file.attribute.AclEntry.Builder = TODO("NIO common stub")
        fun setPermissions(vararg p0: borg.trikeshed.userspace.nio.file.attribute.AclEntryPermission): borg.trikeshed.userspace.nio.file.attribute.AclEntry.Builder = TODO("NIO common stub")
        fun setFlags(p0: Set<borg.trikeshed.userspace.nio.file.attribute.AclEntryFlag>): borg.trikeshed.userspace.nio.file.attribute.AclEntry.Builder = TODO("NIO common stub")
        fun setFlags(vararg p0: borg.trikeshed.userspace.nio.file.attribute.AclEntryFlag): borg.trikeshed.userspace.nio.file.attribute.AclEntry.Builder = TODO("NIO common stub")
    }
}
