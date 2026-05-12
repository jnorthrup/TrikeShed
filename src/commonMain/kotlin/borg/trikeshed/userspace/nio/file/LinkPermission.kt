@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING", "RedundantVisibilityModifier", "unused")

package borg.trikeshed.userspace.nio.file

// Generated from Amazon Corretto JDK 25 java.base NIO public/protected API via javap.
// Coerced to TrikeShed types — no java.* references.
// java.security.BasicPermission has no KMP equivalent; simplified to a named permission.
public class LinkPermission(name: CharSequence, actions: CharSequence? = null) : Exception("LinkPermission: $name" + (actions?.let { ", $it" } ?: ""))
