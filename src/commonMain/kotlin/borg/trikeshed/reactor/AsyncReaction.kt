package borg.trikeshed.reactor

import borg.trikeshed.lib.Join

typealias AsyncReaction = Join<Interest, UnaryAsyncReaction>

interface UnaryAsyncReaction {
    suspend operator fun invoke(key: SelectionKey): AsyncReaction?
}
