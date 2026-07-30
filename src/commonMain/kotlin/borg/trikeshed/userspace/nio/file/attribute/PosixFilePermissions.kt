@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING", "RedundantVisibilityModifier", "unused")

package borg.trikeshed.userspace.nio.file.attribute

// Generated from Amazon Corretto JDK 25 java.base NIO public/protected API via javap.
// Declarations intentionally mirror JDK taxonomy and contain no implementations.
public class PosixFilePermissions {
    companion object {
        fun toString(p0: Set<PosixFilePermission>): String {
            val sb = StringBuilder(9)
            sb.append(if (p0.contains(PosixFilePermission.OWNER_READ)) 'r' else '-')
            sb.append(if (p0.contains(PosixFilePermission.OWNER_WRITE)) 'w' else '-')
            sb.append(if (p0.contains(PosixFilePermission.OWNER_EXECUTE)) 'x' else '-')
            sb.append(if (p0.contains(PosixFilePermission.GROUP_READ)) 'r' else '-')
            sb.append(if (p0.contains(PosixFilePermission.GROUP_WRITE)) 'w' else '-')
            sb.append(if (p0.contains(PosixFilePermission.GROUP_EXECUTE)) 'x' else '-')
            sb.append(if (p0.contains(PosixFilePermission.OTHERS_READ)) 'r' else '-')
            sb.append(if (p0.contains(PosixFilePermission.OTHERS_WRITE)) 'w' else '-')
            sb.append(if (p0.contains(PosixFilePermission.OTHERS_EXECUTE)) 'x' else '-')
            return sb.toString()
        }

        fun fromString(p0: String): Set<PosixFilePermission> {
            require(p0.length == 9) { "Invalid mode" }
            
            fun checkAndAdd(index: Int, expected: Char, perm: PosixFilePermission, perms: MutableSet<PosixFilePermission>) {
                val c = p0[index]
                if (c == expected) {
                    perms.add(perm)
                } else if (c != '-') {
                    throw IllegalArgumentException("Invalid mode")
                }
            }

            val perms = mutableSetOf<PosixFilePermission>()
            checkAndAdd(0, 'r', PosixFilePermission.OWNER_READ, perms)
            checkAndAdd(1, 'w', PosixFilePermission.OWNER_WRITE, perms)
            checkAndAdd(2, 'x', PosixFilePermission.OWNER_EXECUTE, perms)
            checkAndAdd(3, 'r', PosixFilePermission.GROUP_READ, perms)
            checkAndAdd(4, 'w', PosixFilePermission.GROUP_WRITE, perms)
            checkAndAdd(5, 'x', PosixFilePermission.GROUP_EXECUTE, perms)
            checkAndAdd(6, 'r', PosixFilePermission.OTHERS_READ, perms)
            checkAndAdd(7, 'w', PosixFilePermission.OTHERS_WRITE, perms)
            checkAndAdd(8, 'x', PosixFilePermission.OTHERS_EXECUTE, perms)
            return perms
        }

        fun asFileAttribute(p0: Set<PosixFilePermission>): FileAttribute<Set<PosixFilePermission>> {
            val copy = p0.toSet()
            return object : FileAttribute<Set<PosixFilePermission>> {
                override fun name(): String = "posix:permissions"
                override fun value(): Set<PosixFilePermission> = copy
            }
        }
    }
}
