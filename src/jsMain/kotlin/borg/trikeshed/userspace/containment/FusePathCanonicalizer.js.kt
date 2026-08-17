package borg.trikeshed.userspace.containment

class JsFusePathCanonicalizer(private val instanceId: String) : FusePathCanonicalizer {
    private val canonicalToOriginal = mutableMapOf<String, String>()
    private val originalToCanonical = mutableMapOf<String, String>()

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
    JsFusePathCanonicalizer(instanceId)
