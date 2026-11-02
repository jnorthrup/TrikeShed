package borg.trikeshed.tilting.leaf

import platform.posix.getenv
import kotlinx.cinterop.toKString

/** LEAF_CORPUS=<file of concatenated 4096-byte pages>. */
actual fun leafCorpus(): ByteArray? = getenv("LEAF_CORPUS")?.toKString()?.takeIf { it.isNotBlank() }?.let { readFileBytes(it) }
