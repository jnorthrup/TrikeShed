package borg.trikeshed.forge.notion

import kotlinx.serialization.Serializable

@Serializable
@JvmInline
actual value class NotionDatabaseFieldId(val value: String) {
    companion object {
        actual fun fromName(name: String): NotionDatabaseFieldId = NotionDatabaseFieldId(
            name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifBlank { "field" }
        )
    }
}