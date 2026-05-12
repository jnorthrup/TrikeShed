@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING", "RedundantVisibilityModifier", "unused")

package borg.trikeshed.userspace.nio

public open class ByteOrder(private val orderName: String) {
    override fun toString():CharSequence= orderName

    public companion object {
        val BIG_ENDIAN: borg.trikeshed.userspace.nio.ByteOrder = object : ByteOrder("BIG_ENDIAN") {}
        val LITTLE_ENDIAN: borg.trikeshed.userspace.nio.ByteOrder = object : ByteOrder("LITTLE_ENDIAN") {}
        fun nativeOrder(): borg.trikeshed.userspace.nio.ByteOrder = BIG_ENDIAN
    }
}
