package borg.trikeshed.userspace.containment

import borg.trikeshed.job.Sha256Pure

/**
 * FUSE-based path canonicalizer for Legion Doc 04 Layer 1.
 * Intercepts filesystem calls and maps arbitrary semantic strings to deterministic content hashes.
 */
interface FusePathCanonicalizer {
    /**
     * Given an original path name (e.g., "my_folder"), returns the canonicalized version
     * (e.g., "dir_a1b2c3d4" or "file_a1b2c3d4").
     */
    fun canonicalizePath(originalName: String, isDirectory: Boolean): String

    /**
     * Resolves a canonicalized path back to its original name, if tracked by this instance.
     * Returns null if the canonical path is unknown.
     */
    fun resolveOriginal(canonicalName: String): String?
}

expect fun createFusePathCanonicalizer(instanceId: String): FusePathCanonicalizer

/**
 * Helper to generate the canonical name using SHA-256 truncation to 8 hex chars.
 */
fun generateCanonicalName(originalName: String, isDirectory: Boolean, instanceId: String): String {
    val input = "$instanceId:$originalName".encodeToByteArray()
    val hash = Sha256Pure.digest(input)
    val hex = hash.take(4).joinToString("") { byte ->
        (byte.toInt() and 0xFF).toString(16).padStart(2, '0')
    }
    val prefix = if (isDirectory) "dir_" else "file_"
    return "$prefix$hex"
}
