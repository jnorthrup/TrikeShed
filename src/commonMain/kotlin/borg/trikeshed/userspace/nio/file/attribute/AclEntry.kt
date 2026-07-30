@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING", "RedundantVisibilityModifier", "unused")

package borg.trikeshed.userspace.nio.file.attribute

// Generated from Amazon Corretto JDK 25 java.base NIO public/protected API via javap.
// Declarations intentionally mirror JDK taxonomy and contain no implementations.
public class AclEntry private constructor(
    private val _type: borg.trikeshed.userspace.nio.file.attribute.AclEntryType,
    private val _principal: borg.trikeshed.userspace.nio.file.attribute.UserPrincipal,
    private val _permissions: Set<borg.trikeshed.userspace.nio.file.attribute.AclEntryPermission>,
    private val _flags: Set<borg.trikeshed.userspace.nio.file.attribute.AclEntryFlag>
) {
    fun type(): borg.trikeshed.userspace.nio.file.attribute.AclEntryType = _type
    fun principal(): borg.trikeshed.userspace.nio.file.attribute.UserPrincipal = _principal
    fun permissions(): Set<borg.trikeshed.userspace.nio.file.attribute.AclEntryPermission> = _permissions
    fun flags(): Set<borg.trikeshed.userspace.nio.file.attribute.AclEntryFlag> = _flags

    override fun equals(p0: Any?): Boolean {
        if (this === p0) return true
        if (p0 == null || p0 !is AclEntry) return false
        return _type == p0._type && _principal == p0._principal && _permissions == p0._permissions && _flags == p0._flags
    }

    private fun hash(p0: Int, p1: Any): Int = p0 * 127 + p1.hashCode()

    override fun hashCode(): Int {
        var h = _type.hashCode()
        h = hash(h, _principal)
        h = hash(h, _permissions)
        h = hash(h, _flags)
        return h
    }

    override fun toString(): String {
        val sb = StringBuilder()
        sb.append(_principal.getName()).append(":")
        if (_permissions.isNotEmpty()) {
            sb.append(_permissions.joinToString("/"))
        }
        sb.append(":")
        if (_flags.isNotEmpty()) {
            sb.append(_flags.joinToString("/"))
        }
        sb.append(":")
        sb.append(_type.name)
        return sb.toString()
    }

    companion object {
        fun newBuilder(): borg.trikeshed.userspace.nio.file.attribute.AclEntry.Builder = Builder()
        fun newBuilder(p0: borg.trikeshed.userspace.nio.file.attribute.AclEntry): borg.trikeshed.userspace.nio.file.attribute.AclEntry.Builder = Builder(p0)
    }

    public class Builder internal constructor(
        private var _type: borg.trikeshed.userspace.nio.file.attribute.AclEntryType? = null,
        private var _principal: borg.trikeshed.userspace.nio.file.attribute.UserPrincipal? = null,
        private var _permissions: Set<borg.trikeshed.userspace.nio.file.attribute.AclEntryPermission> = emptySet(),
        private var _flags: Set<borg.trikeshed.userspace.nio.file.attribute.AclEntryFlag> = emptySet()
    ) {
        internal constructor(entry: AclEntry) : this(entry.type(), entry.principal(), entry.permissions(), entry.flags())

        fun build(): borg.trikeshed.userspace.nio.file.attribute.AclEntry {
            checkState()
            return AclEntry(_type!!, _principal!!, _permissions, _flags)
        }

        private fun checkState() {
            if (_type == null) throw IllegalStateException("Missing type component")
            if (_principal == null) throw IllegalStateException("Missing who component")
        }

        fun setType(p0: borg.trikeshed.userspace.nio.file.attribute.AclEntryType): borg.trikeshed.userspace.nio.file.attribute.AclEntry.Builder {
            this._type = p0
            return this
        }

        fun setPrincipal(p0: borg.trikeshed.userspace.nio.file.attribute.UserPrincipal): borg.trikeshed.userspace.nio.file.attribute.AclEntry.Builder {
            this._principal = p0
            return this
        }

        fun setPermissions(p0: Set<borg.trikeshed.userspace.nio.file.attribute.AclEntryPermission>): borg.trikeshed.userspace.nio.file.attribute.AclEntry.Builder {
            this._permissions = p0.toSet()
            return this
        }

        fun setPermissions(vararg p0: borg.trikeshed.userspace.nio.file.attribute.AclEntryPermission): borg.trikeshed.userspace.nio.file.attribute.AclEntry.Builder {
            this._permissions = p0.toSet()
            return this
        }

        fun setFlags(p0: Set<borg.trikeshed.userspace.nio.file.attribute.AclEntryFlag>): borg.trikeshed.userspace.nio.file.attribute.AclEntry.Builder {
            this._flags = p0.toSet()
            return this
        }

        fun setFlags(vararg p0: borg.trikeshed.userspace.nio.file.attribute.AclEntryFlag): borg.trikeshed.userspace.nio.file.attribute.AclEntry.Builder {
            this._flags = p0.toSet()
            return this
        }
    }
}
