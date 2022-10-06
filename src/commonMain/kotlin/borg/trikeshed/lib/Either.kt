package borg.trikeshed.lib


/**
 * Either is a data type that represents a value of two possible types (a disjoint union).
 * An instance of Either is either an instance of Left or Right.
 *
 * internally uses TakeIf and TakeUnless to implement the Either type
 * @see <a href="https://en.wikipedia.org/wiki/Disjoint_union">Disjoint Union</a>
 *
 *
 * @param Left the left type
 * @param Right the right type
 */
data class Either<Left, Right>(val left: Left? = null, val right: Right? = null) {

    /**
     * @return true if this is a [Right], false otherwise
     */
    val isRight: Boolean
        get() = right != null

    /**
     * @return true if this is a [Left], false otherwise
     */
    val isLeft: Boolean
        get() = left != null

    /**
     * @return the value from this [Right] or the [default] value if this is a [Left]
     */
    fun getRightOrDefault(default: Right) = right ?: default

    /**
     * @return the value from this [Left] or the [default] value if this is a [Right]
     */
    fun getLeftOrDefault(default: Left) = left ?: default

    /**
     * @return the value from this [Right] or null if this is a [Left]
     */
    fun getRightOrNull() = right

    /**
     * @return the value from this [Left] or null if this is a [Right]
     */
    fun getLeftOrNull() = left

    /**
     * @return the value from this [Right] or throws [NoSuchElementException] if this is a [Left]
     */
    fun getRightOrThrow(): Right = right ?: throw NoSuchElementException("Either.right.value on Left")

    /**
     * @return the value from this [Left] or throws [NoSuchElementException] if this is a [Right]
     */
    fun getLeftOrThrow(): Left = left ?: throw NoSuchElementException("Either.left.value on Right")

    /**
     * Applies [ifLeft] if this is a [Left] or [ifRight] if this is a [Right].
     */
    inline fun <T> fold(ifLeft: (Left) -> T, ifRight: (Right) -> T): T = when {
        isLeft -> ifLeft(left!!)
        else -> ifRight(right!!)
    }

    /**
     * Applies [ifLeft] if this is a [Left] or returns this if this is a [Right].
     */
    inline fun <T> takeLeft(ifLeft: (Left) -> T): Either<T, Right> = when {
        isLeft -> Either(ifLeft(left!!), right)
        else -> this as Either<T, Right>
    }

    /**
     * Applies [ifRight] if this is a [Right] or returns this if this is a [Left].
     */
    inline fun <T> takeRight(ifRight: (Right) -> T): Either<Left, T> = when {
        isRight -> Either(left, ifRight(right!!))
        else -> this as Either<Left, T>
    }

    /**
     * Applies [ifLeft] if this is a [Left] or returns this if this is a [Right].
     */
    inline fun <T> takeLeftIf(predicate: (Left) -> Boolean, ifLeft: (Left) -> T): Either<T, Right> = when {
        isLeft && predicate(left!!) -> Either(ifLeft(left!!), right)
        else -> this as Either<T, Right>
    }

    /**
     * Applies [ifRight] if this is a [Right] or returns this if this is a [Left].
     */
    inline fun <T> takeRightIf(predicate: (Right) -> Boolean, ifRight: (Right) -> T): Either<Left, T> = when {
        isRight && predicate(right!!) -> Either(left, ifRight(right!!))
        else -> this as Either<Left, T>

    }

    /**
     * Applies [ifLeft] if this is a [Left] or returns this if this is a [Right].
     */

    inline fun <T> takeLeftUnless(predicate: (Left) -> Boolean, ifLeft: (Left) -> T): Either<T, Right> = when {
        isLeft && !predicate(left!!) -> Either(ifLeft(left!!), right)
        else -> this as Either<T, Right>

    }

    /**
     * Applies [ifRight] if this is a [Right] or returns this if this is a [Left].
     */

    inline fun <T> takeRightUnless(predicate: (Right) -> Boolean, ifRight: (Right) -> T): Either<Left, T> = when {
        isRight && !predicate(right!!) -> Either(left, ifRight(right!!))
        else -> this as Either<Left, T>

    }

    /**
     * Applies [ifLeft] if this is a [Left] or returns this if this is a [Right].
     */

    inline fun <T> takeLeftIfNotNull(ifLeft: (Left) -> T): Either<T, Right> = when {
        isLeft && left != null -> Either(ifLeft(left!!), right)
        else -> this as Either<T, Right>

    }

    /**
     * Applies [ifRight] if this is a [Right] or returns this if this is a [Left].
     */

    inline fun <T> takeRightIfNotNull(ifRight: (Right) -> T): Either<Left, T> = when {
        isRight && right != null -> Either(left, ifRight(right!!))
        else -> this as Either<Left, T>

    }

    /**
     * Applies [ifLeft] if this is a [Left] or returns this if this is a [Right].
     */

    inline fun <T> takeLeftIfNull(ifLeft: () -> T): Either<T, Right> = when {
        isLeft && left == null -> Either(ifLeft(), right)
        else -> this as Either<T, Right>

    }

    /**
     * Applies [ifRight] if this is a [Right] or returns this if this is a [Left].
     */

    inline fun <T> takeRightIfNull(ifRight: () -> T): Either<Left, T> = when {
        isRight && right == null -> Either(left, ifRight())
        else -> this as Either<Left, T>

    }

    /**
     * Applies [ifLeft] if this is a [Left] or returns this if this is a [Right].
     */

    inline fun <T> takeLeftIfNotNullOr(predicate: (Left) -> Boolean, ifLeft: (Left) -> T): Either<T, Right> = when {
        isLeft && left != null && predicate(left!!) -> Either(ifLeft(left!!), right)
        else -> this as Either<T, Right>

    }

    /**
     * Applies [ifRight] if this is a [Right] or returns this if this is a [Left].
     */

    inline fun <T> takeRightIfNotNullOr(predicate: (Right) -> Boolean, ifRight: (Right) -> T): Either<Left, T> = when {
        isRight && right != null && predicate(right!!) -> Either(left, ifRight(right!!))
        else -> this as Either<Left, T>

    }

    /**
     * Applies [ifLeft] if this is a [Left] or returns this if this is a [Right].
     */

    inline fun <T> takeLeftIfNotNullUnless(predicate: (Left) -> Boolean, ifLeft: (Left) -> T): Either<T, Right> = when {
        isLeft && left != null && !predicate(left!!) -> Either(ifLeft(left!!), right)
        else -> this as Either<T, Right>

    }

    /**
     * Applies [ifRight] if this is a [Right] or returns this if this is a [Left].
     */

    inline fun <T> takeRightIfNotNullUnless(predicate: (Right) -> Boolean, ifRight: (Right) -> T): Either<Left, T> = when {
        isRight && right != null && !predicate(right!!) -> Either(left, ifRight(right!!))
        else -> this as Either<Left, T>

    }
}