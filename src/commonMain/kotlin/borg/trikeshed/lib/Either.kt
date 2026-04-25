package borg.trikeshed.lib


/** Either: sum type without stdlib */
sealed interface Either<out L, out R> {
    class Left<L>(val value: L) : Either<L, Nothing>
    class Right<R>(val value: R) : Either<Nothing, R>

    val isLeft: Boolean get() = this is Left
    val isRight: Boolean get() = this is Right
    val leftOrNull: L? get() = (this as? Left)?.value
    val rightOrNull: R? get() = (this as? Right)?.value

    fun <T> fold(ifLeft: (L) -> T, ifRight: (R) -> T): T =
        if (isLeft) ifLeft(leftOrNull!!) else ifRight(rightOrNull!!)

    fun <T> mapLeft(f: (L) -> T): Either<T, R> =
        if (isLeft) Left(f(leftOrNull!!)) else Right(rightOrNull!!)

    fun <T> mapRight(f: (R) -> T): Either<L, T> =
        if (isRight) Right(f(rightOrNull!!)) else Left(leftOrNull!!)

    companion object {
        fun <L, R> left(value: L): Either<L, R> = Left(value)
        fun <L, R> right(value: R): Either<L, R> = Right(value)
    }
}
