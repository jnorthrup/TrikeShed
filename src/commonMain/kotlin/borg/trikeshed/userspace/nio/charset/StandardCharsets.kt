@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING", "RedundantVisibilityModifier", "unused")

package borg.trikeshed.userspace.nio.charset

public class StandardCharsets {
    public companion object {
        public val US_ASCII: Charset = Charset.forName("US-ASCII")
        public val ISO_8859_1: Charset = Charset.forName("ISO-8859-1")
        public val UTF_8: Charset = Charset.forName("UTF-8")
        public val UTF_16BE: Charset = Charset.forName("UTF-16BE")
        public val UTF_16LE: Charset = Charset.forName("UTF-16LE")
        public val UTF_16: Charset = Charset.forName("UTF-16")
        public val UTF_32BE: Charset = UTF_8
        public val UTF_32LE: Charset = UTF_8
        public val UTF_32: Charset = UTF_8
    }
}
