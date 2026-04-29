package borg.trikeshed.lib

typealias Series2<A, B> = Series<Join<A, B>>

/** Explicit helper for pairwise series join. */
fun <A, B> join(a: Series<A>, b: Series<B>): Series2<A, B> = a.zip(b)

/**
 * Pairwise join with [ReificationContext].
 *
 * When [ctx] is non-null and [ReificationContext.maxDepth] would force
 * materialization, the input series are materialized ([cow]) before
 * zipping.  This flattens nested stair shapes, trading memory for
 * shallower access on the resulting Series2.
 *
 * @param ctx  reification depth cap; null preserves original behavior
 */
fun <A, B> join(a: Series<A>, b: Series<B>, ctx: ReificationContext?): Series2<A, B> {
    if (ctx == null) return join(a, b)
    val ra = if (ctx.maxDepth <= 0 && a !is CowSeriesHandle<*>) a.materialize() else a
    val rb = if (ctx.maxDepth <= 0 && b !is CowSeriesHandle<*>) b.materialize() else b
    @Suppress("UNCHECKED_CAST")
    return join(ra as Series<A>, rb as Series<B>)
}

/** Mutable wrapper for pairwise join; no performance guarantees. */
fun <A, B> joinMutable(a: Series<A>, b: Series<B>): MutableSeries<Join<A, B>> = join(a, b).cow

val <T, I> Series2<T, I>.left: Series<T>
    get() = (this as? ReifiedSplitSeries2<T, I>)?.leftSeries ?: this.α(Join<T, I>::a)

val <T, I> Series2<I, T>.right: Series<T>
    get() = (this as? ReifiedSplitSeries2<I, T>)?.rightSeries ?: this.α(Join<I, T>::b)


//left join
operator fun <A, B> Series<Series2<A, B>>.unaryMinus(): Series<Series<A>> =
    this α Series2<A, B>::left

//right join
operator fun <A, B> Series<Series2<A, B>>.unaryPlus(): Series<Series<B>> = this α Series2<A, B>::right

/** Promote a single Join into a one-element Series2 for pipeline composition. */
fun <A, B> Join<A, B>.toSeries2(): Series2<A, B> = object : Series2<A, B> {
    override val a: Int get() = 1
    override val b: (Int) -> Join<A, B> get() = { this@toSeries2 }
}
