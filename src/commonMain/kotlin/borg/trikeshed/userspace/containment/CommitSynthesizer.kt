package borg.trikeshed.userspace.containment

object CommitSynthesizer {
    /**
     * Re-normalizes a patch and strips author metadata to enforce a
     * centralized system identity before it enters shared storage.
     */
    fun synthesize(patch: String, policy: Layer4ArtifactPolicy): String {
        var processed = patch
        if (policy.stripAuthorMetadata) {
            processed = processed.lineSequence().filterNot { line ->
                val lower = line.lowercase()
                lower.startsWith("author:") ||
                lower.startsWith("co-authored-by:") ||
                lower.startsWith("signed-off-by:")
            }.joinToString("\n")
        }
        if (policy.deterministicFormatter) {
            processed = DeterministicFormatter.format(processed)
        }
        return processed
    }
}
