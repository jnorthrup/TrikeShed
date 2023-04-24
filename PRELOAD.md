copilot healper to get on the page quickly

```kotlin 
interface Join<A, B> {
    val a: A
    val b: B
    operator fun component1(): A = a//destructuring 1&2
    operator fun component2(): B = b
    val pair: Pair<A, B> get() = Pair(a, b); ...
}

/** * exactly like "to" for "Join" but with a different (and shorter!) name */
inline infix fun <A, B> A.j(b: B): Join<A, B> = Join(this, b)
typealias Twin<T> = Join<T, T>
typealias Series<T> = Join<Int, (Int) -> T>

val <T> Series<T>.size: Int get() = a

/** index operator for Series*/
operator fun <T> Series<T>.get(i: Int): T = b(i)
val <T> Series<T>.`▶`: IterableSeries<T> get() = this as? IterableSeries ?: IterableSeries(this)
/**Left Identity Function */
inline val <T> T.`↺`: () -> T get() = leftIdentity
/*lazy series conversion */ inline infix fun <X, C, V : Series<X>> V.α(crossinline xform: (X) -> C): Series<C> =
    size j { i -> xform(this[i]) }
/*iterable conversion*/ infix fun <X, C, Subject : Iterable<X>> Subject.α[...]

[...] dozens of monadic and fp mix -ins and specializations
```