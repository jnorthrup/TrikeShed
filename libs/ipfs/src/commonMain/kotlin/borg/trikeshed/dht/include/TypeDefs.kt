package borg.trikeshed.dht.include

import borg.trikeshed.dht.id.NUID
import borg.trikeshed.lib.Join

typealias Address = String
typealias Route<TNum> = Join<NUID<TNum>, Address>

inline fun zz_map(x: Long): ULong = ((x.toULong()) shl 1) xor (-((x.toULong()) shr 63).toLong()).toULong()

inline fun zz_unmap(y: ULong): Long = ((y shr 1) xor ((-(y and 1.toULong()).toLong()).toULong())).toLong()