package borg.trikeshed.lib

interface Either<Left, Right> {
    val leftOrNull: Left? get() = null
    val rightOrNull: Right? get() = null
    val isLeft: Boolean get() = leftOrNull != null
    val isRight: Boolean get() = rightOrNull != null

    fun <T> fold(ifLeft: (Left) -> T, ifRight: (Right) -> T): T = leftOrNull?.let(ifLeft) ?: ifRight(rightOrNull!!)

    fun <T> mapLeft(f: (Left) -> T): Either<T, Right> = leftOrNull?.let(f)?.let(::left) ?: right(rightOrNull!!)
    fun <T> mapRight(f: (Right) -> T): Either<Left, T> = rightOrNull?.let(f)?.let(::right) ?: left(leftOrNull!!)
    fun <T> map(f: (Right) -> T): Either<Left, T> = mapRight(f)

    fun <T> flatMapLeft(f: (Left) -> Either<T, Right>): Either<T, Right> = leftOrNull?.let(f) ?: right(rightOrNull!!)
    fun <T> flatMapRight(f: (Right) -> Either<Left, T>): Either<Left, T> = rightOrNull?.let(f) ?: left(leftOrNull!!)

    companion object {
        fun <Left, Right> left(left: Left): Either<Left, Right> = object : Either<Left, Right> {
            override val leftOrNull: Left get() = left
        }

        fun <Left, Right> right(right: Right): Either<Left, Right> = object : Either<Left, Right> {
            override val rightOrNull: Right get() = right
        }
    }
}