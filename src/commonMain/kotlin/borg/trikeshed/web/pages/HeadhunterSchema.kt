package borg.trikeshed.web.pages

/**
 * One editable field of a job-agent record. [type] is an input type (`text`, `textarea`, `url`, `email`,
 * `tel`, `date`), `replacements`, `@<kind>` for a record reference, or `choice` with [choices].
 */
class HeadhunterField(val name: String, val label: String, val type: String, val required: Boolean = false, val choices: List<String> = emptyList())

/** Record kinds, their forms, and the page's section names (headhunter.js). */
object HeadhunterSchema {
    val labels: Map<String, String> = linkedMapOf(
        "evidence" to "evidence", "source" to "source", "employer" to "employer", "listing" to "listing", "contact" to "contact",
        "representation" to "representation", "application" to "application", "artifact" to "artifact", "review" to "review", "action" to "action",
    )
    val categories: List<String> = listOf("resume", "experience", "project", "commentary", "correction")
    val sections: List<String> = listOf("applications", "evidence", "records", "sources", "program")
    val reviewable: List<String> = listOf("listing", "contact", "representation", "application", "action")
    val related: List<String> = listOf("employerId", "listingId", "contactId", "applicationId", "sourceId", "correctionOf")

    private fun choice(name: String, label: String, vararg values: String) = HeadhunterField(name, label, "choice", false, values.toList())

    val forms: Map<String, List<HeadhunterField>> = linkedMapOf(
        "evidence" to listOf(
            HeadhunterField("title", "Title", "text", true), HeadhunterField("category", "Category", "choice", false, categories),
            HeadhunterField("text", "Evidence, context, or correction", "textarea", true), HeadhunterField("correctionOf", "Corrects evidence", "@evidence"),
        ),
        "source" to listOf(
            HeadhunterField("title", "Source name", "text", true), HeadhunterField("origin", "Origin or upload", "text"),
            HeadhunterField("url", "Source URL", "url"), choice("mode", "Retrieval method", "http"),
            HeadhunterField("startAfter", "Keep text after this exact marker", "text"), HeadhunterField("endBefore", "Stop before this exact marker", "text"),
            HeadhunterField("replacements", "Literal text corrections", "replacements"), HeadhunterField("notes", "Source notes", "textarea"),
        ),
        "employer" to listOf(
            HeadhunterField("name", "Employer name", "text", true), HeadhunterField("domain", "Website or domain", "text"), HeadhunterField("notes", "Notes", "textarea"),
        ),
        "listing" to listOf(
            HeadhunterField("title", "Job title", "text", true), HeadhunterField("employerId", "Saved employer", "@employer"),
            HeadhunterField("employer", "Employer name", "text"), HeadhunterField("requisition", "Requisition", "text"),
            HeadhunterField("url", "Listing URL", "url"), HeadhunterField("sourceId", "Source", "@source"),
            HeadhunterField("contactId", "Contact", "@contact"), HeadhunterField("text", "Listing text", "textarea", true),
        ),
        "contact" to listOf(
            HeadhunterField("name", "Contact name", "text", true), HeadhunterField("role", "Role", "text"), HeadhunterField("email", "Email", "email"),
            HeadhunterField("phone", "Phone", "tel"), HeadhunterField("employerId", "Employer", "@employer"), HeadhunterField("notes", "Notes", "textarea"),
        ),
        "representation" to listOf(
            HeadhunterField("employerId", "Employer", "@employer"), HeadhunterField("listingId", "Listing", "@listing"),
            HeadhunterField("contactId", "Recruiter or contact", "@contact"), choice("status", "Status", "active", "released", "expired"),
            HeadhunterField("startDate", "Start date", "date"), HeadhunterField("endDate", "End date", "date"),
            HeadhunterField("notes", "Terms and scope of representation", "textarea"),
        ),
        "application" to listOf(
            HeadhunterField("title", "Working title", "text"), HeadhunterField("listingId", "Listing", "@listing", true),
            HeadhunterField("contactId", "Contact", "@contact"), choice("status", "Status", "research", "preparing", "ready", "closed"),
            HeadhunterField("notes", "Application notes", "textarea"),
        ),
        "action" to listOf(
            HeadhunterField("applicationId", "Application", "@application", true), choice("type", "Action", "email", "phone", "application", "note"),
            choice("status", "Record type", "prepared", "reported"), HeadhunterField("notes", "What was prepared or actually done", "textarea", true),
            HeadhunterField("reference", "Supporting reference", "text"),
        ),
    )

    /** The visible section for a location hash; unknown names fall back to applications. */
    fun section(hash: String): String {
        val name = hash.drop(1).substringBefore('?')
        return if (name in sections) name else "applications"
    }

    fun choiceLabel(v: String): String = if (v == "http") "HTTP" else v.replace("-", " ")
}
