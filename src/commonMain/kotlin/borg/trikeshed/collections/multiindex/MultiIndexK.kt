package borg.trikeshed.collections.multiindex

import borg.trikeshed.lib.OpK
import borg.trikeshed.lib.Series

typealias IndexSpecId = Int

sealed class MultiIndexK<out R> : OpK<R>() {
    class ByHash<K : Any>(val extractor: (Any?) -> K) : MultiIndexK<(K) -> IndexSpecId?>()
    class ByNonUniqueHash<K : Any>(val extractor: (Any?) -> K) : MultiIndexK<(K) -> Series<IndexSpecId>>()
    class ByOrder<K : Comparable<K>>(val extractor: (Any?) -> K) : MultiIndexK<Series<IndexSpecId>>()
    class ByRange<K : Comparable<K>>(val extractor: (Any?) -> K) : MultiIndexK<(K, K) -> Series<IndexSpecId>>()
    data object BySequence : MultiIndexK<Series<IndexSpecId>>()
    data object Elements : MultiIndexK<Series<Any?>>()
}
