package borg.trikeshed.couch

/**
 * ConfixRepositoryView - Adapter for projecting generic repositories onto Confix views.
 */
interface ConfixRepositoryView {
    operator fun get(id: String): ConfixDocStoreEntry?
}
