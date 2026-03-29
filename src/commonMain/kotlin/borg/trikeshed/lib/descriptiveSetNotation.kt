package borg.trikeshed.lib

private fun <E> Set<E>.disjointUnion(other: Set<E>) = union(other).toSet()
private fun <E> Set<E>.cartesianProduct(other: Set<E>) = flatMap { a -> other.map { b -> a to b } }.toSet()
private fun <E> Set<E>.symmetricDifference(other: Set<E>): Set<E> {
    val result = HashSet(this)
    for (element in other) if (!result.remove(element)) result.add(element)
    return result
}

object emptySetSymbol : Set<Nothing> by emptySet()

infix fun <A> A.isIn(set: Set<A>): Boolean = set.contains(this)
infix fun <A> A.isNotIn(set: Set<A>): Boolean = !set.contains(this)
infix fun <A> Set<A>.properSubsetOf(other: Set<A>): Boolean = containsAll(other) && size < other.size
infix fun <A> Set<A>.subsetOf(other: Set<A>): Boolean = containsAll(other)
infix fun <A> Set<A>.properSupersetOf(other: Set<A>): Boolean = other.containsAll(this) && other.size < size
infix fun <A> Set<A>.supersetOf(other: Set<A>): Boolean = other.containsAll(this)
infix fun <A> Set<A>.unionWith(other: Set<A>): Set<A> = union(other)
infix fun <A> Set<A>.intersectWith(other: Set<A>): Set<A> = intersect(other)
infix fun <A> Set<A>.minusSet(other: Set<A>): Set<A> = subtract(other)
infix fun <A> Set<A>.symmetricDifferenceWith(other: Set<A>): Set<A> = symmetricDifference(other)
infix fun <A> Set<A>.complementOf(other: Set<A>): Set<A> = subtract(other)
infix fun <A> Set<A>.cartesianWith(other: Set<A>): Set<Pair<A, A>> = cartesianProduct(other)
infix fun <A> Set<A>.disjointUnionWith(other: Set<A>): Set<A> = disjointUnion(other)
infix fun <A> Set<A>.coproductWith(other: Set<A>): Set<A> = disjointUnion(other)
