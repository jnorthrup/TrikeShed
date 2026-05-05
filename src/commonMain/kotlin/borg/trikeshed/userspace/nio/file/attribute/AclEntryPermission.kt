@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING", "RedundantVisibilityModifier", "unused")

package borg.trikeshed.userspace.nio.file.attribute

// Generated from Amazon Corretto JDK 25 java.base NIO public/protected API via javap.
// Declarations intentionally mirror JDK taxonomy and contain no implementations.
expect enum class AclEntryPermission {
    READ_DATA, WRITE_DATA, APPEND_DATA, READ_NAMED_ATTRS, WRITE_NAMED_ATTRS, EXECUTE, DELETE_CHILD, READ_ATTRIBUTES, WRITE_ATTRIBUTES, DELETE, READ_ACL, WRITE_ACL, WRITE_OWNER, SYNCHRONIZE, LIST_DIRECTORY, ADD_FILE, ADD_SUBDIRECTORY
}
