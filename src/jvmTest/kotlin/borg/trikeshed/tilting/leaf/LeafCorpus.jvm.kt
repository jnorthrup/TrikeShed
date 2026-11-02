package borg.trikeshed.tilting.leaf

import java.io.File

/** -Dleaf.corpus=<file of concatenated 4096-byte pages>. */
actual fun leafCorpus(): ByteArray? =
    System.getProperty("leaf.corpus")?.takeIf { it.isNotBlank() }?.let { File(it).readBytes() }
