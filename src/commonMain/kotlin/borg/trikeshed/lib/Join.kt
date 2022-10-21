package borg.trikeshed.lib

import kotlin.js.JsExport
import kotlin.js.JsName


/**
 * Joins two things.  Pair semantics but distinct in the symbol naming
 */
interface Join<A, B> {
    val a: A
    val b: B
    operator fun component1(): A = a
    operator fun component2(): B = b

    val pair: Pair<A, B>
        get() = Pair(a, b)

    companion object {
        //the Join factory method
        operator fun <A, B> invoke(a1: A, b1: B) = object : Join<A, B> {
            override val a get() = a1
            override val b get() = b1
        }

        //the Series factory method
        operator fun <T> invoke(vararg items: T) = object : Series<T> {
            override val a: Int get() = items.size
            override val b: (Int) -> T get() = items::get
        }

        //the Pair factory method
        operator fun <A, B> invoke(pair: Pair<A, B>) = object : Join<A, B> {
            override val a get() = pair.first
            override val b get() = pair.second
        }

        //Twin factory method
        fun <T> Twin(a: T, b: T): Twin<T> = object : Twin<T> {
            override val a get() = a
            override val b get() = b
        }

        //the Map factory method
        operator fun <A, B> invoke(map: Map<A, B>) = object : Series<Join<A, B>> {
            override val a: Int get() = map.size
            override val b: (Int) -> Join<A, B> get() = { map.entries.elementAt(it).let { Join(it.key, it.value) } }
        }

        inline fun <reified B> emptySeriesOf(): Series<B> = 0 j { TODO("Empty list is incomplete") }
    }
}

typealias Twin<T> = Join<T, T>

inline val <reified A> Join<A, *>.first: A get() = this.a
inline val <reified B> Join<*, B>.second: B get() = this.b

/**
 * exactly like "to" for "Join" but with a different (and shorter!) name
 */
infix fun <A, B> A.j(b: B): Join<A, B> = Join(this, b)

/** α
 * (λx.M[x]) → (λy.M[y])	α-conversion
 * https://en.wikipedia.org/wiki/Lambda_calculus
 *
 * in kotlin terms, λ above is a lambda expression and M is a function and the '.' is the body of the lambda
 * therefore the function M is the receiver of the extension function and the lambda expression is the argument
 *
 *  the simplest possible kotlin example of λx.M[x] is
 *  ` { x -> M(x) } ` making the delta symbol into lambda braces and the x into a parameter and the M(x) into the body
 *
 *  to understand `(λx.M[x]) → (λy.M[y])` we need to understand the difference between the two lambdas
 *  the first lambda is a function that takes a parameter x and returns the result of calling M with x
 *  the second lambda is a function that takes a parameter y and returns the result of calling M with y
 *  the difference is that the first lambda is a function of x and the second lambda is a function of y
 *
 *  is M generally assumed to be a function placeholder when reading wikipedia or should we assume that we need to understand what M functoin is outputting?
 *  λ above is a lambda expression and M is a function and the '.' is the body of the lambda
 *
 *
 *  alpha-conversion in lambda calculus is the process of changing the name of a bound variable in a lambda expression.
 *
 *  this function does not merely change the name of a variable but also changes function return type.
 *
 *  the definition of conversion applies in lambda calculus but not in kotlin.  why is that?
 *  because in kotlin, the lambda expression is not a function but a function type.  the function type is the return type
 *
 *  the function type is the return type of the lambda expression.  the lambda expression is the body of the function
 *
 *  does lambda calculus have function types?  yes, it does.  it is called a lambda abstraction and it is written as
 *  λx.M where x is the parameter and M is the body of the lambda abstraction
 *
 *  does lambda calculus have function calls?  yes, it does.  it is written as M[x] where M is the function and x is the argument
 *  in kotlin M[x] is written as M(x) and in lambda calculus M[x] is written as M x
 *
 *  in lambda calculus, the lambda abstraction is the function type and the lambda expression is the body of the function
 *
 *  does lambda calculus have a programming language?  yes, it does.  it is called untyped lambda calculus.
 *  the conversion from untyped lambda calculus to typed lambda calculus is called type inference
 *
 *  which type of lambda calculus is the text `(λx.M[x]) → (λy.M[y])` showing?  it is showing untyped lambda calculus
 *
 *  how do we convert untyped lambda calculus to typed lambda calculus?  we need to add type annotations to the lambda abstractions
 *  the type annotation for the first lambda abstraction is (x:A) -> B where A is the type of the parameter and B is the return type
 *  the type annotation for the second lambda abstraction is (y:C) -> D where C is the type of the parameter and D is the return type
 *
 *      (λx.M[x]) → (λy.M[y]) (untype lambda calculus)
 *      (x:A) -> B → (y:C) -> D (typed lambda calculus)
 *      (kotlin)  (A) -> B → (C) -> D
 *
 *      in kotlin implementation this is the code:
 *      `fun <A, B, C, D> ((A) -> B).alpha(f: (C) -> D): (C) -> D = f`
 *
 *      the type of the receiver is (A) -> B
 *      the type of the argument is (C) -> D
 *      the type of the return value is (C) -> D
 *
 *      the receiver is the first lambda abstraction
 *      the argument is the second lambda abstraction
 *      the return value is the second lambda abstraction
 *
 *      the receiver is the function type of the first lambda abstraction
 *      the argument is the function type of the second lambda abstraction
 *      the return value is the function type of the second lambda abstraction
 *     the operators of untyped lambda, typed lambda, and kotlin are shown in the markdown table below
 *
 *     the kotlin definition `infix fun <X, C, Y : (X) -> C, V : Series<X>> V.α(xform: Y): Join<Int, (Int) -> C> =  size j { i -> xform(this[i]) }`
 *     is the same as the untyped lambda calculus definition `(λx.M[x]) → (λy.M[y])` except that the parameter names are different
 *     the parameter names are different because the parameter names are not part of the type signature
 *     the parameter names are part of the function body
 *     the parameter names are part of the function body because the function body is a lambda expression
 *     the implication of → is that the function body is a lambda expression
 *     the meaning  of → is described in wikipedia as "the function type constructor"
 *     the meaning of α is described in wikipedia as "alpha-conversion" which is the process of changing the name of a bound variable in a lambda expression
 *
 *      *     the difference between changing a name in untyped calculus and changing a name in kotlin is that in untyped
 *     calculus the name is part of the type signature whereas in kotlin the name is part of the function body which
 *     is a lambda expression.
 *     in typed lambda calculus the type signature is the function type and the function body is the lambda expression.
 *
 *   shown below in markdown table form are the concepts of lambda calculus, typed lambda calculus, and kotlin
 *
 *  |concept name | untyped lambda calculus | typed lambda calculus | kotlin |
 *  |-------------|-------------------------|-----------------------|--------|
 *  |lambda abstraction| λx.M | (x:A) -> B | (A) -> B |
 *  |lambda expression| M[x] | M(x) | M(x) |
 *  |function type| (x:A) -> B | (x:A) -> B | (A) -> B |
 *  |function body| M | M(x) | { x -> M(x) } |
 *  |function call| M x | M(x) | M(x) |
 *  |function placeholder| M | M | M |
 *  |function parameter| x | x | x |
 *  |function return type| B | B | B |
 *  |function type constructor| → | → | → |
 *  |function type annotation| (x:A) -> B | (x:A) -> B | (A) -> B |
 *  |function type signature| (x:A) -> B | (x:A) -> B | (A) -> B |
 *
 *
 *
 *
 *  in kotlin type signatures exist but they are not part of the function type
 *  in lambda calculus type signatures are part of the function type and used for type inference
 *  the definition of a lambda abstraction is a function type and the definition of a lambda expression is a function body
 *
 *  kotlin function types are not the same as lambda calculus function types because kotlin function types do not have type signatures
 *  kotlin function types are the same as lambda calculus function types because kotlin function types have a function type constructor
 *
 *  in kotlin function type declaration the difference between (A)->B and (a:A)->B specific to kotlin is that the
 *  parameter name is part of the function type declaration, and is a legal identifier in kotlin, whereas the parameter
 *  name is not part of the function type declaration in lambda calculus.
 *
 *  classes in kotlin are not the same as lambda calculus function types because classes in kotlin have a class type constructor and
 *  lambda calculus function types have a function type constructor
 *
 *   for clarification the meaning of `->` and `→` are shown below in markdown table form
 *
 *   | concept name | untyped lambda calculus | typed lambda calculus | kotlin |
 *   |--------------|-------------------------|-----------------------|--------|
 *   |function type constructor| → | → | -> |
 *
 *   does this mean that they describe the same thing in two different lexicons ? yes, they do
 *
 *   common lambda calculus concepts are shown below in markdown table form
 *
 *   | concept name | untyped lambda calculus | typed lambda calculus | kotlin |
 *   |--------------|-------------------------|-----------------------|--------|
 *   |lambda abstraction| λx.M | (x:A) -> B | (A) -> B |
 *   |lambda expression| M[x] | M(x) | M(x) |
 *   |function type| (x:A) -> B | (x:A) -> B | (A) -> B |
 *   |function body| M | M(x) | { x -> M(x) } |
 *   |function call| M x | M(x) | M(x) |
 *   |function placeholder| M | M | M |
 *
 *   common category theory concepts are shown below in markdown table form
 *
 *   | concept name | untyped lambda calculus | typed lambda calculus | kotlin |
 *   |--------------|-------------------------|-----------------------|--------|
 *   |function parameter| x | x | x |
 *   |function return type| B | B | B |
 *   |function type constructor| → | → | -> |
 *   |function type annotation| (x:A) -> B | (x:A) -> B | (A) -> B |
 *   |function type signature| (x:A) -> B | (x:A) -> B | (A) -> B |
 *
 *   common set thoery notations are shown below in markdown table form
 *
 *   | concept name | untyped lambda calculus | typed lambda calculus | kotlin |
 *   |--------------|-------------------------|-----------------------|--------|
 *   |function type constructor| → | → | -> |
 *
 *
 */

infix fun <X, C, V : Series<X> > V.α(xform: (X)->C): Join<Int, (Int) -> C> = size j { i -> xform(this[i]) }

fun <A, B, C, D> ((A) -> B).alpha(f: (C) -> D): (C) -> D = f
//simple example
//fun main() {
//    val f = { x: Int -> x + 1 }
//    val g = { y: Int -> y * 2 }
//    val h = f.alpha(g)
//    println(h(1))
//    //result is 4
//
//    //for Series type
//    val s = Series(1, 2, 3)
//    val t = { x: Int -> x + 1 }
//    val u = { y: Int -> y * 2 }
//    val v = s.α(t)
//    println(v)
//    //result is [2, 4, 6]
//}


/**
 * provides unbounded access to first and last rows beyond the existing bounds of 0 until size
 */
val <T> Join<Int, (Int) -> T>.infinite: Join<Int, (Int) -> T>
    get() = Int.MAX_VALUE j { x: Int ->
        this.b(
            when {
                x < 0 -> 0
                size <= x -> size.dec()
                else -> x
            }
        )
    }

/**
 * index by enum
 */
operator fun <S, E : Enum<E>> Join<Int, (Int) -> S>.get(e: E): S = get(e.ordinal)

/** Series toList
 * @return an AbstractList<T> of the Series<T>
 */
fun <T> Series<T>.toList(): AbstractList<T> = object : AbstractList<T>() {
    override val size: Int =a
    override fun get(index: Int): T = b(index)
}

fun Series<Byte>.toArray(): ByteArray =
    ByteArray(size) { i -> get(i) }

fun Series<Char>.toArray(): CharArray =
    CharArray(size) { i -> get(i) }

fun Series<Int>.toArray(): IntArray =
    IntArray(size) { i -> get(i) }

fun Series<Boolean>.toArray(): BooleanArray =
    BooleanArray(size) { i -> get(i) }

fun Series<Long>.toArray(): LongArray =
    LongArray(size) { i -> get(i) }

fun Series<Float>.toArray(): FloatArray =
    FloatArray(size) { i -> get(i) }

fun Series<Double>.toArray(): DoubleArray =
    DoubleArray(size) { i -> get(i) }

fun Series<Short>.toArray(): ShortArray =
    ShortArray(size) { i -> get(i) }

inline fun <reified T> Join<Int, (Int) -> T>.toArray(): Array<T> =
    Array<T>(size) { i -> get(i) }

fun <T> Array<T>.toSeries(): Join<Int, (Int) -> T> =
    (size j ::get) as Join<Int, (Int) -> T>

val <T> T.rightIdentity: () -> T get() = { this }


infix fun <C, B : (Long) -> C> LongArray.α(m: B): Join<Int, (Int) -> C> = this.size j { i: Int -> m(this[i]) }

// do all the other primitive arrays

infix fun <C, B : (Int) -> C> IntArray.α(m: B): Join<Int, (Int) -> C> = this.size j { i: Int -> m(this[i]) }

infix fun <C, B : (Float) -> C> FloatArray.α(m: B): Join<Int, (Int) -> C> = this.size j { i: Int -> m(this[i]) }

infix fun <C, B : (Double) -> C> DoubleArray.α(m: B): Join<Int, (Int) -> C> = this.size j { i: Int -> m(this[i]) }

infix fun <C, B : (Short) -> C> ShortArray.α(m: B): Join<Int, (Int) -> C> = this.size j { i: Int -> m(this[i]) }

infix fun <C, B : (Byte) -> C> ByteArray.α(m: B): Join<Int, (Int) -> C> = this.size j { i: Int -> m(this[i]) }

infix fun <C, B : (Char) -> C> CharArray.α(m: B): Join<Int, (Int) -> C> = this.size j { i: Int -> m(this[i]) }

infix fun <C, B : (Any) -> C> Array<Any>.α(m: B): Join<Int, (Int) -> C> = this.size j { i: Int -> m(this[i]) }

infix fun <C, B : (Boolean) -> C> BooleanArray.α(m: B): Join<Int, (Int) -> C> = this.size j { i: Int -> m(this[i]) }

/**
 * series get by iterable
 */
operator fun <T> Series<T>.get(index: Iterable<Int>): Series<T> {

    val array = IntArray(index.count()) { i -> index.elementAt(i) }
    return this[array]
}

/**
 * series get by Series<Int>
 */
operator fun <T> Series<T>.get(index: Series<Int>): Series<T> {

    val array = IntArray(index.size) { i -> index[i] }; return this[array]
}

/**
 * series get by array
 */
operator fun <T> Series<T>.get(index: IntArray): Series<T> = Series(index.size) { i -> this[index[i]] }

/**
 * series get by intRange
 */
operator fun <T> Series<T>.get(index: IntRange): Series<T> = Series(index.count()) { i -> this[index.first + i] }

