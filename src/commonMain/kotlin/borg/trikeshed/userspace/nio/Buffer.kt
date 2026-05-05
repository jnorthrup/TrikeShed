@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING", "RedundantVisibilityModifier", "unused")

package borg.trikeshed.userspace.nio

public open class Buffer(protected val capacity: Int) {
    protected var position0: Int = 0
    protected var limit0: Int = capacity
    protected var mark0: Int = -1

    public open fun capacity(): Int = capacity

    public open fun position(): Int = position0

    public open fun position(p0: Int): Buffer {
        require(p0 in 0..limit0) { "position must be between 0 and limit" }
        position0 = p0
        if (mark0 > position0) mark0 = -1
        return this
    }

    public open fun limit(): Int = limit0

    public open fun limit(p0: Int): Buffer {
        require(p0 in 0..capacity) { "limit must be between 0 and capacity" }
        limit0 = p0
        if (position0 > limit0) position0 = limit0
        if (mark0 > limit0) mark0 = -1
        return this
    }

    public open fun mark(): Buffer {
        mark0 = position0
        return this
    }

    public open fun reset(): Buffer {
        require(mark0 >= 0) { "Mark has not been set" }
        position0 = mark0
        return this
    }

    public open fun clear(): Buffer {
        position0 = 0
        limit0 = capacity
        mark0 = -1
        return this
    }

    public open fun flip(): Buffer {
        limit0 = position0
        position0 = 0
        mark0 = -1
        return this
    }

    public open fun rewind(): Buffer {
        position0 = 0
        mark0 = -1
        return this
    }

    public open fun remaining(): Int = limit0 - position0

    public open fun hasRemaining(): Boolean = remaining() > 0

    public open fun isReadOnly(): Boolean = false

    public open fun hasArray(): Boolean = false

    public open fun array(): Any = throw UnsupportedOperationException("Buffer has no accessible array")

    public open fun arrayOffset(): Int = throw UnsupportedOperationException("Buffer has no accessible array")

    public open fun isDirect(): Boolean = false

    public open fun slice(): Buffer = throw UnsupportedOperationException("slice is not supported")

    public open fun slice(p0: Int, p1: Int): Buffer = throw UnsupportedOperationException("slice with range is not supported")

    public open fun duplicate(): Buffer = throw UnsupportedOperationException("duplicate is not supported")
}
