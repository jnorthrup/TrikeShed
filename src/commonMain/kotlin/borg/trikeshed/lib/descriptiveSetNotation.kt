package borg.trikeshed.lib


private fun <E> Set<E>.disjointUnion(other: Set<E>) = this.union(other).toSet()


//        Set theory infix functions and operatioins for kotlin
//        ∅
//        Denotes the empty set, and is more often written {\displaystyle \emptyset }\emptyset . Using set-builder notation, it may also be denoted {\displaystyle \{\}}\{\}.
object `∅` : Set<Nothing> by emptySet()

private fun <E> Set<E>.cartesianProduct(other: Set<E>) = this.flatMap { a -> other.map { b -> a to b } }.toSet()


////        ∪
////        Denotes the union of two sets, and is more often written {\displaystyle \cup }\cup . Using set-builder notation, it may also be denoted {\displaystyle \{\,x\mid x\in A\text{ or }x\in B\,\}}\{\,x\mid x\in A\text{ or }x\in B\,\}.
//infix fun <A> Set<A>.`∪`(other:Set<A>) = this.union(other)


//        #
//        1.  Number of elements: {\displaystyle \#{}S}{\displaystyle \#{}S} may denote the cardinality of the set S. An alternative notation is {\displaystyle |S|}|S|; see {\displaystyle |\square |}{\displaystyle |\square |}.
//        2.  Primorial: {\displaystyle n{}\#}{\displaystyle n{}\#} denotes the product of the prime numbers that are not greater than n.
//        3.  In topology, {\displaystyle M\#N}{\displaystyle M\#N} denotes the connected sum of two manifolds or two knots.
//        4.  In knot theory, {\displaystyle M\#N}{\displaystyle M\#N} denotes the connected sum of two knots. The notation {\displaystyle M\#N}{\displaystyle M\#N} is also used to denote the connected sum of two manifolds.


//        ∈
//        Denotes set membership, and is read "in" or "belongs to". That is, {\displaystyle x\in S}x\in S means that x is an element of the set S.
infix fun <A> A.`∈`(set: Set<A>): Boolean = set.contains(this)

//        ∉
//        Means "not in". That is, {\displaystyle x\notin S}{\displaystyle x\notin S} means {\displaystyle \neg (x\in S)}{\displaystyle \neg (x\in S)}.
infix fun <A> A.`∉`(set: Set<A>): Boolean = !set.contains(this)

//        ⊂
//        Denotes set inclusion. However two slightly different definitions are common.

infix fun <A> Set<A>.`⊂`(other: Set<A>): Boolean = this.containsAll(other) && this.size < other.size


//        ⊆
//        {\displaystyle A\subseteq B}A\subseteq B means that A is a subset of B. Used for emphasizing that equality is possible, or when the second definition of {\displaystyle A\subset B}A\subset B is used.
infix fun <A> Set<A>.`⊆`(other: Set<A>): Boolean = this.containsAll(other)


//        ⊊
//        {\displaystyle A\subsetneq B}{\displaystyle A\subsetneq B} means that A is a proper subset of B. Used for emphasizing that {\displaystyle A\neq B}A\neq B, or when the first definition of {\displaystyle A\subset B}A\subset B is used.
infix fun <A> Set<A>.`⊊`(other: Set<A>): Boolean = this.containsAll(other) && this.size < other.size

//        ⊃, ⊇, ⊋
//        Denote the converse relation of {\displaystyle \subset }\subset , {\displaystyle \subseteq }\subseteq , and {\displaystyle \subsetneq }\subsetneq  respectively. For example, {\displaystyle B\supset A}B\supset A is equivalent to {\displaystyle A\subset B}A\subset B.
infix fun <A> Set<A>.`⊃`(other: Set<A>): Boolean = other.containsAll(this) && other.size < this.size
infix fun <A> Set<A>.`⊇`(other: Set<A>): Boolean = other.containsAll(this)
infix fun <A> Set<A>.`⊋`(other: Set<A>): Boolean = other.containsAll(this) && other.size < this.size


//        ∪
//        Denotes set-theoretic union, that is, {\displaystyle A\cup B}A\cup B is the set formed by the elements of A and B together. That is, {\displaystyle A\cup B=\{x\mid (x\in A)\lor (x\in B)\}}{\displaystyle A\cup B=\{x\mid (x\in A)\lor (x\in B)\}}.
infix fun <A> Set<A>.`∪`(other: Set<A>): Set<A> = this.union(other)
//        ∩
//        Denotes set-theoretic intersection, that is, {\displaystyle A\cap B}A\cap B is the set formed by the elements of both A and B. That is, {\displaystyle A\cap B=\{x\mid (x\in A)\land (x\in B)\}}{\displaystyle A\cap B=\{x\mid (x\in A)\land (x\in B)\}}.

infix fun <A> Set<A>.`∩`(other: Set<A>): Set<A> = this.intersect(other)

//        ∖
//        Set difference; that is, {\displaystyle A\setminus B}{\displaystyle A\setminus B} is the set formed by the elements of A that are not in B. Sometimes, {\displaystyle A-B}A-B is used instead; see – in § Arithmetic operators.

infix fun <A> Set<A>.`∖`(other: Set<A>): Set<A> = this.subtract(other)


private fun <E> Set<E>.symmetricDifference(other: Set<E>): Set<E> {
    val result = HashSet(this)
    for (element in other)
        if (!result.remove(element))
            result.add(element)
    return result
}

/**
⊖ or {\displaystyle \triangle }\triangle
Symmetric difference: that is, {\displaystyle A\ominus B}A\ominus B or {\displaystyle A\operatorname {\triangle } B}{\displaystyle A\operatorname {\triangle } B} is the set formed by the elements that belong to exactly one of the two sets A and B.
 */

infix fun <A> Set<A>.`⊖`(other: Set<A>): Set<A> = this.symmetricDifference(other)


//        ∁
//        1.  With a subscript, denotes a set complement: that is, if {\displaystyle B\subseteq A}B\subseteq A, then {\displaystyle \complement _{A}B=A\setminus B}{\displaystyle \complement _{A}B=A\setminus B}.
//        2.  Without a subscript, denotes the absolute complement; that is, {\displaystyle \complement A=\complement _{U}A}{\displaystyle \complement A=\complement _{U}A}, where U is a set implicitly defined by the context, which contains all sets under consideration. This set U is sometimes called the universe of discourse.

infix fun <A> Set<A>.`∁`(other: Set<A>): Set<A> = this.subtract(other)


//        ×
//        See also × in § Arithmetic operators.
//        1.  Denotes the Cartesian product of two sets. That is, {\displaystyle A\times B}A\times B is the set formed by all pairs of an element of A and an element of B.
//        2.  Denotes the direct product of two mathematical structures of the same type, which is the Cartesian product of the underlying sets, equipped with a structure of the same type. For example, direct product of rings, direct product of topological spaces.
//        3.  In category theory, denotes the direct product (often called simply product) of two objects, which is a generalization of the preceding concepts of product.


infix fun <A> Set<A>.`×`(other: Set<A>): Set<Pair<A, A>> = this.cartesianProduct(other)

//        ⊔
//        Denotes the disjoint union. That is, if A and B are sets then {\displaystyle A\sqcup B=\left(A\times \{i_{A}\}\right)\cup \left(B\times \{i_{B}\}\right)}{\displaystyle A\sqcup B=\left(A\times \{i_{A}\}\right)\cup \left(B\times \{i_{B}\}\right)} is a set of pairs where iA and iB are distinct indices discriminating the members of A and B in {\displaystyle A\sqcup B}{\displaystyle A\sqcup B}.

infix fun <A> Set<A>.`⊔`(other: Set<A>): Set<A> = this.disjointUnion(other)


//        ∐
//        1.  An alternative to {\displaystyle \sqcup }\sqcup .
//        2.  Denotes the coproduct of mathematical structures or of objects in a category.

infix fun <A> Set<A>.`∐`(other: Set<A>): Set<A> = this.disjointUnion(other)

