package borg.trikeshed.couch.handle

/** Multiplatform handle state enum used by CollectionHandle implementations */
expect enum class HandleState {
    OPEN, SEALED, CLOSED
}
