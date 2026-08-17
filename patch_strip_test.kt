fun stripAuthorMetadata(patch: String): String {
    return patch.lineSequence().filterNot {
        val lower = it.lowercase()
        lower.startsWith("author:") ||
        lower.startsWith("co-authored-by:") ||
        lower.startsWith("signed-off-by:")
    }.joinToString("\n")
}
