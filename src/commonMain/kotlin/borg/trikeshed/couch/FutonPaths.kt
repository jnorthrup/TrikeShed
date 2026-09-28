package borg.trikeshed.couch

/** The Couch URLs the Futon browser addresses, one database at a time. */
object FutonPaths {
    const val ALL_DBS = "/_all_dbs"

    fun segment(s: String): String = buildString {
        for (b in s.encodeToByteArray()) {
            val c = (b.toInt() and 0xFF).toChar()
            if (c.isLetterOrDigit() && c.code < 0x80 || c in "-_.~") append(c)
            else append('%').append(((b.toInt() and 0xFF) or 0x100).toString(16).substring(1).uppercase())
        }
    }

    /** A doc id keeps its `/` separators; each piece is encoded. */
    fun id(id: String): String = id.split('/').joinToString("/") { segment(it) }

    fun db(db: String): String = "/" + segment(db)

    /** Every design document of [db], bodies included. */
    fun designDocs(db: String): String =
        db(db) + "/_all_docs?include_docs=true&startkey=" + segment("\"_design/\"") + "&endkey=" + segment("\"_design0\"")

    /** `/{db}/_design/{ddoc}/_view/{view}?{query}`; [ddoc] with or without its `_design/` prefix. */
    fun view(db: String, ddoc: String, view: String, query: String): String =
        db(db) + "/_design/" + segment(ddoc.removePrefix("_design/")) + "/_view/" + segment(view) +
            (query.trim().removePrefix("?").takeIf { it.isNotEmpty() }?.let { "?$it" } ?: "")
}
