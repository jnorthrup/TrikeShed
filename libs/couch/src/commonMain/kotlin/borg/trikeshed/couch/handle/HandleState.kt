package borg.trikeshed.couch.handle

/** Handle state used by CollectionHandle implementations across all targets. */
enum class HandleState {
    OPEN,
    SEALED,
    CLOSED
}
