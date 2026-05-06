@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING", "RedundantVisibilityModifier", "unused")

package borg.trikeshed.userspace.nio.charset

public class StandardCharsets {
    public companion object {
        public val ISO_8859_1: Charset = Charset.forName("ISO-8859-1")
        public val UTF_8: Charset = Charset.forName("UTF-8")
    }
}
