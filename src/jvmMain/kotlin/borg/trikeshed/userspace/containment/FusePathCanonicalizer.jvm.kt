package borg.trikeshed.userspace.containment

import java.util.concurrent.ConcurrentHashMap

/**
 * JVM actual implementation of FusePathCanonicalizer, using an in-memory map.
 */
class JvmFusePathCanonicalizer(private val instanceId: String) : FusePathCanonicalizer {
    private val canonicalToOriginal = ConcurrentHashMap<String, String>()
    private val originalToCanonical = ConcurrentHashMap<String, String>()

    override fun canonicalizePath(originalName: String, isDirectory: Boolean): String {
        return originalToCanonical.getOrPut(originalName) {
            val canonical = generateCanonicalName(originalName, isDirectory, instanceId)
            canonicalToOriginal[canonical] = originalName
            canonical
        }
    }

    override fun resolveOriginal(canonicalName: String): String? {
        return canonicalToOriginal[canonicalName]
    }
}

actual fun createFusePathCanonicalizer(instanceId: String): FusePathCanonicalizer =
    JvmFusePathCanonicalizer(instanceId)
