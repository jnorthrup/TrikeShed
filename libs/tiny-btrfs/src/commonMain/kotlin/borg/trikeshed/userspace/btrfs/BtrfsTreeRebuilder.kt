package borg.trikeshed.userspace.btrfs

import borg.trikeshed.lib.*

import borg.trikeshed.tinybtrfs.*

/**
 * Rebuild a B+Tree from a set of key/value pairs.
 *
 * This is a helper to initialize a B+Tree without having to do individual insertions.
 *
 * @param diskAdapter The backing storage for nodes.
 * @param kvPairs     The key/value pairs to insert, sorted by key.
 */
fun BPlusTree.rebuildFromSorted(diskAdapter: DiskAdapter, kvPairs: List<Pair<Series<Byte>, Series<Byte>>>) {
    // For simplicity, we'll just do individual inserts.
    // A more sophisticated bulk-loading algorithm could be implemented here.
    for ((key, value) in kvPairs) {
        this.insert(key, value, diskAdapter)
    }
}
