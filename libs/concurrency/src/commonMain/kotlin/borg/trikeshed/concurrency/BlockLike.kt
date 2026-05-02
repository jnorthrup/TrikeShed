package borg.trikeshed.concurrency

// Minimal interface describing a block with a rowCount — used by MvccBlockStore and tests.
interface BlockLike {
    val rowCount: Int
}
