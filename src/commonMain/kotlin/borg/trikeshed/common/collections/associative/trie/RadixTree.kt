package borg.trikeshed.common.collections.associative.trie

import borg.trikeshed.common.collections._a
import borg.trikeshed.common.collections.binarySearch
import borg.trikeshed.lib.*
import kotlin.jvm.JvmInline

/**
 * a node in a prefix tree (trie)
 */
sealed interface RadixTreeNode<C : Comparable<C>, K : Series<C>> {
    val key: Series<C>
    operator fun plus(key: Series<C>): RadixTreeNode<C, K>
}

/**
 * implement a prefix tree (trie) for a series of values of type T (which must be comparable)
 */
@Suppress("UNCHECKED_CAST")
class RadixTree<T : Comparable<T>, K : Series<T>>(
    private var root: RadixTreeNode<T, K> = RadixTreeBranch(emptySeries<T>(), mutableListOf())
) : RadixTreeNode<T, K> {
    override fun plus(key: Series<T>): RadixTreeNode<T, K> = run { root.plus(key) }.also { root = it }
    override val key: K get() = root.key as K
}

class RadixTreeBranch<C : Comparable<C>, K : Series<C>>(
    override val key: Series<C>,
    private var children: MutableList<RadixTreeNode<C, K>>
) : RadixTreeNode<C, K> {
    override fun plus(key1: Series<C>): RadixTreeNode<C, K> {
        if (this.key.compareTo(key1) == 0) return this.apply {
            if (!children.first().key.isEmpty()) children.add(0, RadixTreeLeaf(emptySeries<C>() as K))
        }

        val cp = key.commonPrefixWith(key1)
        val newBranchKey: Series<C> = (this.key.take(cp.size).takeUnless { it.isEmpty() } ?: emptySeries())
        val oldKey: Series<C> = (this.key.drop(cp.size).takeUnless { it.isEmpty() } ?: emptySeries())
        val newKey: Series<C> = (key1.drop(cp.size).takeUnless { it.isEmpty() } ?: emptySeries())

        (children.toSeries() α { it.key.cpb }).binarySearch(newKey.cpb).let { index ->
            if (index >= 0) {
                val radixTreeNode: RadixTreeNode<C, K> = children[index]
                if (radixTreeNode is RadixTreeBranch<C, K>) {
                    // Recursively add the key to the child branch
                    children[index] = radixTreeNode.plus(newKey as K)
                } else {
                    // Create a new branch with the leaf and the new key
                    val join = radixTreeNode.plus(newKey)
                    children[index] = join
                }
                return this
            }
        }

        val newLeaf = RadixTreeLeaf(newKey)
        val oldBranch = RadixTreeBranch(oldKey, this.children)
        return RadixTreeBranch(
            newBranchKey,
            mutableListOf(newLeaf, oldBranch).apply { sortBy { it.key.cpb } }
                .toMutableList() as MutableList<RadixTreeNode<C, K>>
        )
    }
}


@JvmInline
value class RadixTreeLeaf<C : Comparable<C>, K : Series<C>>(override val key: K) : RadixTreeNode<C, K> {
    override operator fun plus(key: Series<C>): RadixTreeNode<C, K> {
        val kcpb: CSeries<C> = key.cpb
        val other: CSeries<C> = key.cpb
        if (0 == kcpb.compareTo(other)) return this
        val commonPrefix = key.commonPrefixWith(key)
        val commonPrefixLength = commonPrefix.size
        val s1 = (_a[key.drop(commonPrefixLength).cpb, key.drop(commonPrefixLength).cpb].apply { sort() } α {
            RadixTreeLeaf(it)
        }).toList().toMutableList()
        return RadixTreeBranch(commonPrefix, s1 as MutableList<RadixTreeNode<C, K>>)
    }
}

