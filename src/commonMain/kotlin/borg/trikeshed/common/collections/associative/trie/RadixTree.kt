package borg.trikeshed.common.collections.associative.trie

import borg.trikeshed.common.collections._a
import borg.trikeshed.common.collections.binarySearch
import borg.trikeshed.lib.*
import kotlin.jvm.JvmInline

/**
 * a node in a prefix tree (trie)
 */
sealed interface RadixTreeNode<C : Comparable<C>, K : Series<C>> {
    val key: K
    operator fun plus(key: K): RadixTreeNode<C, K>
}

/**
 * implement a prefix tree (trie) for a series of values of type T (which must be comparable)
 */
@Suppress("UNCHECKED_CAST")
class RadixTree<T : Comparable<T>, K : Series<T>>(
    private var root: RadixTreeNode<T, K> = RadixTreeBranch(emptySeries<T>() as K, mutableListOf())
) : RadixTreeNode<T, K> {
    override fun plus(key: K): RadixTreeNode<T, K> = run { root.plus(key) }.also { root = it }
    override val key: K get() = root.key


}

class RadixTreeBranch<C : Comparable<C>, K : Series<C>>(
    override val key: K,
    var children: MutableList<RadixTreeNode<C, K>>
) : RadixTreeNode<C, K> {
    override operator fun plus(key1: K): RadixTreeNode<C, K> {
        if (this.key.compareTo(key1) == 0) return this.apply {
            if (!children.first().key.isEmpty()) children.add(0, RadixTreeLeaf(emptySeries<C>() as K))
        }

        val cp = key.commonPrefixWith(key1)
        val newBranchKey = this.key.take(cp.size).takeUnless { it.isEmpty() } ?: emptySeries()
        val oldKey: Series<C> = this.key.drop(cp.size).takeUnless { it.isEmpty() } ?: emptySeries()
        val newKey: Series<C> = key1.drop(cp.size).takeUnless { it.isEmpty() } ?: emptySeries()


        (children.toSeries() α { it.key.cpb }).binarySearch(newKey.cpb).let { index ->
            if (index >= 0) {
                val radixTreeNode = children[index]
                val join = radixTreeNode.plus(newKey as K)
                val updatedChild: RadixTreeNode<C, K> = join
                children[index] = updatedChild
                return this
            }
        }

        val newLeaf = RadixTreeLeaf(newKey)
        val oldBranch = RadixTreeBranch(oldKey as K, this.children)
        val newBranch = RadixTreeBranch(
            key = newBranchKey as K,
            mutableListOf(newLeaf as RadixTreeNode<C, K>, oldBranch).apply { sortBy { it.key.cpb } }.toMutableList()
        )
        return newBranch
    }
}


@JvmInline
value class RadixTreeLeaf<C : Comparable<C>, K : Series<C>>(override val key: K) : RadixTreeNode<C, K>,
    Series<C> by key {
    override operator fun plus(key: K): RadixTreeNode<C, K> {
        val kcpb = key.cpb
        val other = key.cpb
        if (0 == kcpb.compareTo(other)) return this
        val commonPrefix: K = key.commonPrefixWith(key) as K
        val commonPrefixLength = commonPrefix.size
        val s1 = (_a[key.drop(commonPrefixLength).cpb, key.drop(commonPrefixLength).cpb].apply { sort() } α {
            RadixTreeLeaf(it as K)
        }).toList()
        return RadixTreeBranch(commonPrefix, s1.toMutableList())
    }
}

