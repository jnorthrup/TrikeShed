@file:Suppress("UNCHECKED_CAST")

package borg.trikeshed.collections

/**
 * LinearHashMap — open-addressing hash map with triangular probing.
 *
 * Probe sequence: h(k) + i*(i+1)/2 mod capacity.
 * Capacity is always a power of 2. With load ≤ 0.5 this sequence visits every
 * slot at most once before wrapping.
 *
 * Invariants:
 *   - capacity is a power of 2
 *   - load factor kept < 0.5 (resize at n >= capacity/2)
 *   - deleted tombstones (DELETED sentinel) allow probe chains to continue
 *   - tombstones are reclaimed on resize
 *
 * Cost: get/put/remove O(1) amortised; resize O(n) amortised.
 * KMP-compatible: pure commonMain, no reflection, no stdlib HashMap.
 *
 * IMPORTANT: keys/values arrays are *fresh* arrays pre-filled with TWO distinct
 * sentinel markers (ABSENT and DELETED). This avoids JS sparse-array quirks
 * where `arrayOfNulls(n)` may emit `undefined` slots that fail `=== null` checks
 * and cause unbounded probe loops on Node. Always check with `isAbsent(k)` /
 * `isDeleted(k)` rather than `=== null` / `=== DELETED`.
 */
typealias LinearHashMap<K, V> = borg.trikeshed.collections.associative.LinearHashMap<K, V>