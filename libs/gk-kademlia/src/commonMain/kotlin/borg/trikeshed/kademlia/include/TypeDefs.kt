@file:Suppress("UNCHECKED_CAST", "NOTHING_TO_INLINE") @file:OptIn(
    ExperimentalUnsignedTypes::class,
    ExperimentalStdlibApi::class
)
package borg.trikeshed.kademlia.include
import borg.trikeshed.lib.Join
import borg.trikeshed.kademlia.id.NUID
typealias Address = String
typealias Route<TNum> = Join<NUID<TNum>, Address>
inline fun zz_map(x: Long): ULong = ((x.toULong()) shl 1) xor (-((x.toULong()) shr 63).toLong()).toULong()
inline fun zz_unmap(y: ULong): Long = ((y shr 1) xor ((-(y and 1.toULong()).toLong()).toULong())).toLong()
