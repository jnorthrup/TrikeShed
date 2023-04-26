package borg.trikeshed.common.collections.associative.trie

import borg.trikeshed.common.collections._a
import borg.trikeshed.lib.*
import kotlin.jvm.JvmInline

/**
 * implement a prefix tree (trie) for a series of values of type T (which must be comparable)
 */
@Suppress("UNCHECKED_CAST")
class RadixTree<T : Comparable<T>> {
    sealed interface RadixTreeNode<C : Comparable<C>, K : Series<C>> : Series<C> {
        operator fun plus(key: K): RadixTreeNode<C, K>
    }


    @JvmInline
    value class RadixTreeLeaf<C : Comparable<C>, K : Series<C>>(val value: K) : RadixTreeNode<C, K>,
        Series<C> by value {
        override fun plus(key: K): RadixTreeNode<C, K> {
            val kcpb = key.cpb
            val other = value.cpb
            if (0 == kcpb.compareTo(other)) return this
            val commonPrefix: K = value.commonPrefixWith(key) as K
            val commonPrefixLength = commonPrefix.size
            val s1 = (_a[value.drop(commonPrefixLength).cpb, key.drop(commonPrefixLength).cpb].apply { sort() } α {
                RadixTreeLeaf(it as K)
            }).toList()
            return RadixTreeBranch(commonPrefix, s1.toMutableList())
        }
    }

    class RadixTreeBranch<C : Comparable<C>, K : Series<C>>(
        value: K,
        private var children: MutableList<RadixTreeNode<C, K>>
    ) : RadixTreeNode<C, K>,
        Series<C> by value {

        override fun plus(key: K): RadixTreeNode<C, K> {
            //key identical
            if (this.compareTo(key) == 0) return this.apply {
                if (!children.first().isEmpty()) children.add(0, RadixTreeLeaf(emptySeries<C>() as K))
            }

            val cp = commonPrefixWith(key)
            val newBranchKey = this.take(cp.size).takeUnless { it.isEmpty() } ?: emptySeries()
            val oldKey = this.drop(cp.size).takeUnless { it.isEmpty() } ?: emptySeries()
            val newKey = key.drop(cp.size).takeUnless { it.isEmpty() } ?: emptySeries()
            val newLeaf = RadixTreeLeaf(newKey as K)
            val oldBranch = RadixTreeBranch(oldKey as K, this.children)
            val newBranch = RadixTreeBranch(
                newBranchKey as K,
                mutableListOf(newLeaf, oldBranch).apply { sortBy(RadixTreeNode<C, K>::cpb) }.toMutableList()
            )
            return newBranch
        }
    }
}





