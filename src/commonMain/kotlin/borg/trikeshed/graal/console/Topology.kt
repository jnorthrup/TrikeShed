package borg.trikeshed.graal.console

/** Terrain row categories: path-classified store planes and the measured runtime planes, one bit each. */
object Topology {
    class Category(val id: String, val label: String, val color: String, val hint: String)

    val categories = listOf(
        Category("git", "Git", "#c798df", "Git paths and pack files (path-classified)"),
        Category("classes", "Classes", "#68c8c1", "Compiled .class files (path-classified)"),
        Category("binaries", "Binaries", "#e7b66b", "Known binary file extensions; unknown blobs remain Other"),
        Category("other", "Other blobs", "#9daeb8", "Source, documents and unclassified content"),
        Category("blocks", "Heap blocks", "#79ba81", "Measured GC pool occupancy blocks, not object addresses"),
        Category("live", "Actual heap", "#ed928e", "Live class histogram, when supplied by the daemon; not allocation samples or an object-reference graph"),
        Category("allocation", "Allocations", "#8fb9ee", "JFR sampled allocation attribution, not retained live bytes"),
    )

    const val ALL = 127

    private val GIT = Regex("(^|/)\\.git(/|$)|(^|/)pack-[0-9a-f]+\\.(pack|idx)$", RegexOption.IGNORE_CASE)
    private val CLASS = Regex("\\.class$", RegexOption.IGNORE_CASE)
    private val BINARY = Regex("\\.(jar|war|zip|gz|bz2|xz|7z|so|dylib|dll|exe|bin|o|a|wasm|hprof|png|jpg|jpeg|gif|webp|ico|pdf|mp[34]|wav|woff2?|ttf)$", RegexOption.IGNORE_CASE)

    /** Category of a store row id; runtime rows carry their own category. */
    fun category(id: String, runtimeCategory: String?): String = when {
        runtimeCategory != null -> runtimeCategory
        GIT.containsMatchIn(id) -> "git"
        CLASS.containsMatchIn(id) -> "classes"
        BINARY.containsMatchIn(id) -> "binaries"
        else -> "other"
    }

    fun bit(id: String): Int { val i = categories.indexOfFirst { it.id == id }; return if (i < 0) 0 else 1 shl i }

    /** The first category whose bit is set in [bits]. */
    fun first(bits: Int): Category? = categories.firstOrNull { bit(it.id) and bits != 0 }
}
