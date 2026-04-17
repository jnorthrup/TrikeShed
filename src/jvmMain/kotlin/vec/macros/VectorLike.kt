@file:Suppress(
    "OVERRIDE_BY_INLINE",
    "NOTHING_TO_INLINE",
    "FunctionName",
    "FINAL_UPPER_BOUND",
    "UNCHECKED_CAST",
    "NonAsciiCharacters",
    "KDocUnresolvedReference",
    "ObjectPropertyName",
    "ClassName",
    "OVERLOADS_WITHOUT_DEFAULT_ARGUMENTS",
)

package vec.macros

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import vec.util.debug
import kotlin.math.max
import kotlin.math.min
import java.nio.ByteBuffer
import java.util.*

typealias Series<T> = Join<Int, (Int) -> T>
typealias Vect02<F, S> = Series<Join<F, S>>
typealias V3ct0r<F, S, T> = Series<Tripl3<F, S, T>>

inline val <T> Series<T>.size: Int get() = first

@Suppress("NonAsciiCharacters")
typealias Matrix<T> = Join<IntArray, (IntArray) -> T>

operator fun <T> Matrix<T>.get(vararg c: Int): T = second(c)

infix fun <O, R, F : (O) -> R> O.`→`(f: F): R = f(this)

operator fun <A, B, R, O : (A) -> B, G : (B) -> R> O.times(b: G): (A) -> R = { a: A -> b(this(a)) }

infix fun <A, B, R, O : (A) -> B, G : (B) -> R, R1 : (A) -> R> O.`→`(b: G): R1 = { a: A -> b(this(a)) } as R1

val <F, S> Vect02<F, S>.left: Series<F> get() = this α { it.a }
val <A, B> Vect02<A, B>.right: Series<B> get() = this α { it.b }
val <A, B> Vect02<A, B>.reify get() = this.α { it.a to it.b }

infix fun <A, B, C, G : (B) -> C, F : (A) -> B, R : (A) -> C> G.`⚬`(f: F): R = { a: A -> a `→` f `→` this } as R

infix fun <A, C, B : (A) -> C, V : Series<A>> V.α(m: B): Join<Int, (Int) -> C> = map(m)

infix fun <A, C, B : (A) -> C, T : Iterable<A>> T.α(m: B): Series<C> = this.map { m(it) }.toVect0r()

infix fun <A, C, B : (A) -> C> List<A>.α(m: B): Join<Int, (Int) -> C> = this.size t2 { i: Int -> m(this[i]) }

infix fun <A, C, B : (A) -> C> Array<out A>.α(m: B): Join<Int, (Int) -> C> = this.size t2 { i: Int -> m(this[i]) }

infix fun <C, B : (Int) -> C> IntArray.α(m: B): Join<Int, (Int) -> C> = (this.size) t2 { i: Int -> m(this[i]) }

infix fun <C, B : (Float) -> C> FloatArray.α(m: B): Join<Int, (Int) -> C> = (this.size) t2 { i: Int -> m(this[i]) }

infix fun <C, B : (Double) -> C> DoubleArray.α(m: B): Join<Int, (Int) -> C> = this.size t2 { i: Int -> m(this[i]) }

infix fun <C, B : (Long) -> C> LongArray.α(m: B): Join<Int, (Int) -> C> = Series(this.size) { i: Int -> this[i] `→` m }

val <T> T.rightIdentity: () -> T get() = { this }

val <T> T.`⟲`: () -> T get() = rightIdentity

fun <S> Series<S>.toSet(opt: MutableSet<S>? = null): MutableSet<S> =
    (
        opt ?: LinkedHashSet<S>(size)
        ).also { hs -> hs.addAll(this.`➤`) }

@JvmInline
value class IterableVect0r<T>(
    val v: Series<T>,
) : Iterable<T>,
    Series<T> {
    override inline fun iterator(): Iterator<T> = v.iterator()

    override inline val first: Int
        inline get() = v.first
    override inline val second: (Int) -> T
        inline get() = v.second
}

operator fun <T> Series<T>.iterator(): Iterator<T> =
    object : Iterator<T> {
        var t = 0

        override fun hasNext(): Boolean = t < size

        override fun next(): T = second(t++)
    }

@JvmName("vlike_List_1")
inline operator fun <reified T> List<T>.get(vararg index: Int): Series<T> = get(index)

@JvmName("vlike_List_Iterable2")
inline operator fun <reified T> List<T>.get(indexes: Iterable<Int>): Join<Int, (Int) -> T> =
    this[indexes.toList().toIntArray()]

@JvmName("vlike_List_IntArray3")
inline operator fun <reified T> List<T>.get(index: IntArray): Series<T> = index α ::get

@JvmName("vlike_Array_1")
operator fun <T> Array<T>.get(vararg index: Int): Series<T> = index α ::get

@JvmName("vlike_Array_Iterable2")
operator fun <T> Array<T>.get(index: Iterable<Int>): Series<T> = index α ::get

@JvmName("vlike_Array_IntArray3")
operator fun <T> Array<T>.get(index: IntArray): Join<Int, (Int) -> T> = index α ::get

@JvmName("vlike_Vect0r_getByInt")
operator fun <T> Series<T>.get(index: Int): T = second(index)

@JvmName("vlike_Vect0r_getVarargInt")
operator fun <T> Series<T>.get(vararg index: Int): Join<Int, (Int) -> T> = get(index)

@JvmName("vlike_Vect0r_getIntIterator")
operator fun <T> Series<T>.get(indexes: Iterable<Int>): Join<Int, (Int) -> T> = this[indexes.toList().toIntArray()]

@JvmName("vlike_Vect0r_getIntArray")
operator fun <T> Series<T>.get(index: IntArray): Join<Int, (Int) -> T> =
    Series(index.size) { ix: Int -> second(index[ix]) }

@JvmName("vlike_Vect0r_toArray")
inline fun <reified T> Series<T>.toArray() = this.let { (_, vf) -> Array<T>(first) { it: Int -> vf(it) } }

fun <T> Series<T>.toList(): List<T> =
    let { v ->
        object : AbstractList<T>() {
            override inline val size get() = v.first
            override inline operator fun get(index: Int) = v.second(index)
        }
    }

fun <T> Series<T>.toSequence(): Sequence<T> =
    this.let { (size, vf) ->
        sequence {
            for (ix in 0 until size) {
                yield(vf(ix))
            }
        }
    }

fun <T> Series<T>.toFlow(): Flow<T> =
    this.let { (size, vf) ->
        flow {
            for (ix in 0 until size) {
                emit(vf(ix))
            }
        }
    }

fun <T, R, V : Series<T>> V.map(fn: (T) -> R): Join<Int, (Int) -> R> = Series(this.size) { it: Int -> fn(second(it)) }

fun <T, R> Series<T>.mapIndexedToList(fn: (Int, T) -> R): List<R> = List(first) { fn(it, second(it)) }

fun <T> Series<T>.forEach(fn: (T) -> Unit): Unit = repeat(size) { fn(second(it)) }

fun <T> Series<T>.forEachIndexed(f: (Int, T) -> Unit) {
    for (x in 0 until size) f(x, second(x))
}

fun <T> vect0rOf(vararg a: T): Series<T> = Series(a.size) { it: Int -> a[it] }

infix fun <T, R> List<T>.zip(other: Series<R>): List<Join<T, R>> = zip(other.`➤`) { a: T, b: R -> a t2 b }

@JvmName("vvzip2f")
fun <T, O, R> Series<T>.zip(
    o: Series<O>,
    f: (T, O) -> R,
) = size t2 { x: Int -> f(this[x], o[x]) }

fun <T, O, K, R> Series<T>.join(
    other: Series<O>,
    thisKeySelector: (T) -> K,
    otherKeySelector: (O) -> K,
    combiner: (T, O) -> R,
): Series<R> {
    val result = mutableListOf<R>()
    for (i in 0 until this.size) {
        val t = this[i]
        val keyT = thisKeySelector(t)
        for (j in 0 until other.size) {
            val o = other[j]
            if (keyT == otherKeySelector(o)) {
                result.add(combiner(t, o))
            }
        }
    }
    return result.toVect0r()
}

@JvmName("vvzip2")
@Suppress("UNCHECKED_CAST")
infix fun <T, O, R : Vect02<T, O>> Series<T>.zip(o: Series<O>): R =
    (min(size, o.size) t2 { x: Int -> (this[x] t2 o[x]) }) as R

@JvmName("combine_Flow")
fun <T> combine(vararg s: Flow<T>): Flow<T> =
    flow {
        for (f: Flow<T> in s) {
            f.collect(this::emit)
        }
    }

@JvmName("combine_Sequence")
fun <T> combine(vararg s: Sequence<T>): Sequence<T> =
    sequence {
        for (sequence: Sequence<T> in s) {
            for (t in sequence) {
                yield(t)
            }
        }
    }

@JvmName("combine_List")
fun <T> combine(vararg a: List<T>): List<T> =
    a.sumOf(List<T>::size).let { size: Int ->
        var x = 0
        var y = 0
        List(size) {
            if (y >= a[x].size) {
                ++x
                y = 0
            }
            a[x][y++]
        }
    }

@JvmName("combine_Array")
inline fun <reified T> combine(vararg a: Array<T>): Array<T> =
    a.sumOf(Array<T>::size).let { size: Int ->
        var x = 0
        var y = 0
        Array(size) { _: Int ->
            if (y >= a[x].size) {
                ++x
                y = 0
            }
            a[x][y++]
        }
    }

fun IntArray.zipWithNext(): Vect02<Int, Int> =
    Series(
        size / 2,
    ) { i: Int ->
        val c: Int = i * 2
        Tw1n(this[c], this[c + 1])
    }

@JvmName("zwnT")
fun <T> Series<T>.zipWithNext(): Vect02<T, T> =
    Series(size / 2) { i: Int ->
        val c = i * 2
        Tw1n(this[c], this[c + 1])
    }

@JvmName("zwnInt")
fun Series<Int>.zipWithNext(): Vect02<Int, Int> =
    Series(size / 2) { i: Int ->
        val c = i * 2
        Tw1n(this[c], this[c + 1])
    }

@JvmName("zwnLong")
fun Series<Long>.zipWithNext(): Vect02<Long, Long> =
    Series(size / 2) { i: Int ->
        val c = i * 2
        Tw1n(this[c], this[c + 1])
    }

inline operator fun <reified K, reified V> Map<K, V>.get(ks: Series<K>): Array<V> =
    this.get(*ks.toList().toTypedArray())

inline operator fun <reified K, reified V> Map<K, V>.get(ks: Iterable<K>): Array<V> =
    this.get(*ks.toList().toTypedArray())

inline operator fun <K, reified V> Map<K, V>.get(vararg ks: K): Array<V> = Array(ks.size) { ix: Int -> get(ks[ix])!! }

infix operator fun IntRange.div(denominator: Int): Series<IntRange> =
    (this t2 (last - first + (1 - first)) / denominator).let { (_: IntRange, subSize: Int): Join<IntRange, Int> ->
        Series(denominator) { x: Int ->
            (subSize * x).let { lower ->
                lower..last.coerceAtMost(lower + subSize - 1)
            }
        }
    }

infix operator fun <T> Vector<T>.div(denominator: Int): Join<Int, (Int) -> Join<Int, (Int) -> T>> =
    (0 until this.size).div(denominator).α { rnge ->
        (this as Series<T>).slice(rnge.first, rnge.last)
    }

val <T> Series<T>.f1rst: T
    get() = get(0)

val <T> Series<T>.last: T
    get() = get(size - 1)

val <T> Series<T>.reverse: Series<T>
    get() = this.size t2 { x: Int -> second(size - 1 - x) }

@JvmOverloads
fun <T, TT : Series<T>, TTT : Series<TT>> TTT.slicex(
    start: Int = 0,
    endExclusive: Int = max(this.size, start),
): TTT =
    (
        this.first t2 { y: Int ->
            this.second(y).let { (_, b) ->
                (1 + endExclusive - start) t2 { x: Int -> b(x + start) }
            }
        }
        ) as TTT

@JvmOverloads
@JvmName("vect0rSlice")
fun <T> Series<T>.slice(
    start: Int = 0,
    endExclusive: Int = this.size,
): Series<T> = (endExclusive - start) t2 { x: Int -> this[x + start] }

infix operator fun <T, P : Series<T>> P.plus(p: P): P = combine(this, p) as P

infix operator fun <T, P : Series<T>> P.plus(t: T): P = combine(this, (1 t2 t.`⟲`) as P) as P

fun <T : Byte> Series<T>.toByteArray(): ByteArray = ByteArray(size) { i: Int -> get(i) }
fun <T : Char> Series<T>.toCharArray(): CharArray = CharArray(size) { i: Int -> get(i) }
fun <T : Int> Series<T>.toIntArray(): IntArray = IntArray(size) { i: Int -> get(i) }
fun <T : Boolean> Series<T>.toBooleanArray(): BooleanArray = BooleanArray(size) { i: Int -> get(i) }
fun <T : Long> Series<T>.toLongArray(): LongArray = LongArray(size) { i: Int -> get(i) }
fun <T : Float> Series<T>.toFloatArray(): FloatArray = FloatArray(size) { i: Int -> get(i) }
fun <T : Double> Series<T>.toDoubleArray(): DoubleArray = DoubleArray(size) { i: Int -> get(i) }

@JvmName("AToVec")
fun <T> Array<T>.toVect0r(): Series<T> = (size t2 ::get) as Series<T>

@JvmName("LToVec")
fun <T> List<T>.toVect0r(): Series<T> = (size t2 ::get) as Series<T>

@JvmName("IAToVec")
fun IntArray.toVect0r(): Series<Int> = (size t2 ::get) as Series<Int>

@JvmName("LAToVec")
fun LongArray.toVect0r(): Series<Long> = (size t2 ::get) as Series<Long>

@JvmName("DAToVec")
fun DoubleArray.toVect0r(): Series<Double> = (size t2 ::get) as Series<Double>

@JvmName("FAToVec")
fun FloatArray.toVect0r(): Series<Float> = (size t2 ::get) as Series<Float>

@JvmName("BAToVec")
fun ByteArray.toVect0r(): Series<Byte> = (size t2 ::get) as Series<Byte>

@JvmName("CAToVec")
fun CharArray.toVect0r(): Series<Char> = (size t2 ::get) as Series<Char>

@JvmName("CSToVec")
fun CharSequence.toVect0r(): Series<Char> = (length t2 ::get) as Series<Char>

@JvmName("StrToVec")
fun String.toVect0r(): Series<String> = (length t2 ::get) as Series<String>

fun <T : Boolean> BitSet.toVect0r(): Join<Int, (Int) -> Boolean> = (length() t2 { it: Int -> get(it) })

fun Series<Int>.sum(): Int = takeIf { this.size > 0 }?.`➤`?.reduce(Int::plus) ?: 0
fun Series<Long>.sum(): Long = takeIf { this.size > 0 }?.`➤`?.reduce(Long::plus) ?: 0L
fun Series<Double>.sum(): Double = takeIf { this.size > 0 }?.`➤`?.reduce(Double::plus) ?: 0.0
fun Series<Float>.sum(): Float = takeIf { this.size > 0 }?.`➤`?.reduce(Float::plus) ?: 0f

@JvmName("FlowToVec")
suspend fun <T> Flow<T>.toVect0r(): Series<T> = this.toList().toVect0r()

@JvmName("BBToVec")
fun ByteBuffer.toVect0r(): Series<Byte> =
    slice().let { slice -> Series(slice.remaining()) { ix: Int -> slice.get(ix) } }

@JvmName("IntRangeToVec")
fun IntRange.toVect0r(): Series<Int> {
    fun SimpleIntRangeToVec(): Series<Int> = last.inc() - first t2 { x: Int -> first + x }
    return if (this.step != 1 || this.first >= this.last) this.toList() α { it } else SimpleIntRangeToVec()
}

@JvmName("IterableToVec")
fun <T> Iterable<T>.toVect0r(): Series<T> = this.toList() α { it }

@JvmName("SeqToVec")
fun <T> Sequence<T>.toVect0r(): Series<T> = this.toList() α { it }

val <X> Series<Series<X>>.T: Join<Int, (Int) -> Join<Int, (Int) -> X>>
    get() {
        val rows = size
        val cols = this[0].size
        return cols t2 { y: Int ->
            rows t2 { x: Int ->
                this[x][y]
            }
        }
    }

val <T> Series<T>.`➤`: IterableVect0r<T>
    get() = IterableVect0r(this)

val <T> Series<T>.infinite: Series<T>
    get() =
        Int.MAX_VALUE t2 { x: Int ->
            this[when {
                x < 0 -> 0
                size <= x -> size.dec()
                else -> x
            }]
        }

@JvmName("getIndexByEnum")
operator fun <S, E : Enum<E>> Series<S>.get(e: E): S = get(e.ordinal)

@JvmName("sparseVect0rMap")
fun <K : Int, V> Map<K, V>.sparseVect0rMap() =
    let { top ->
        ((this as? SortedMap)?.keys ?: keys.sorted()).toIntArray().let { k ->
            0 t2
                if (top.size <= 16) {
                    { x: Int ->
                        var r: V? = null
                        var i = 0
                        do {
                            if (k[i++] == x) r = top[x]
                        } while (i < size && r == null)
                        r.also {
                            assert(it == top[x])
                        }
                    }
                } else {
                    { x: Int ->
                        k.binarySearch(x).takeUnless { 0 < it }?.let {
                            top[x].debug {
                                assert(it == top[x])
                            }
                        }
                    }
                }
        }
    }

@JvmName("sparseVect0r")
fun <K : Int, V> Map<K, V>.sparseVect0r(): Series<V?> =
    let { top ->
        ((this as? SortedMap)?.entries ?: entries.sortedBy { it.key }).toTypedArray().let { entries ->
            val k = keys.toIntArray()
            0 t2
                if (top.size <= 16) {
                    { x: Int ->
                        var r: V? = null
                        var i = 0
                        do {
                            if (k[i++] == x) r = entries[i].value
                        } while (i < size && r == null)
                        r.also { assert(it == top[x]) }
                    }
                } else {
                    { x: Int ->
                        k.binarySearch(x).takeUnless { 0 < it }?.let { i ->
                            (entries[i].value).also {
                                assert(it == top[x])
                            }
                        }
                    }
                }
        }
    }

object Vect02_ {
    fun <F, S> Vect02<F, S>.toMap(theMap: MutableMap<F, S>? = null): MutableMap<F, S> =
        toList().map(Join<F, S>::pair).let { paris ->
            theMap?.let { paris.toMap(theMap) } ?: paris.toMap(HashMap(paris.size))
        }

    @JvmInline
    value class Vect02rl<A, B>(val a: Vect02<A, B>) {
        val left: Series<A> get() = a α { it.a }
        val right: Series<B> get() = a α { it.b }
        val reify get() = a.toList()
    }

    operator fun <A, B> invoke(a: Vect02<A, B>) = Vect02rl(a)
}

operator fun <T> Series<T>.div(d: Int): Series<Series<T>> = (0 until size) / d α { this[it] }
