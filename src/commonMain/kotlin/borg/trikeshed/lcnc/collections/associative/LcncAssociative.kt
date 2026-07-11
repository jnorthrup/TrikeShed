package borg.trikeshed.lcnc.collections.associative

import borg.trikeshed.lib.Series
import borg.trikeshed.lib.j

/**
 * Represents the associative mapping gems for the Lcnc taxonomy.
 * This handles dynamic schemas, properties, and metadata associations.
 */

/**
 * Defines the type of a property (column) in a Lcnc Database.
 */
enum class PropertyType {
    TITLE, TEXT, NUMBER, SELECT, MULTI_SELECT, DATE, PEOPLE, FILES, CHECKBOX, URL, EMAIL, PHONE_NUMBER, FORMULA, RELATION, ROLLUP, CREATED_TIME, CREATED_BY, LAST_EDITED_TIME, LAST_EDITED_BY
}

/**
 * Defines a single property's schema within a Database.
 */
data class PropertySchema(
    val id: String,
    val name: String,
    val type: PropertyType,
    // Type-specific configuration (e.g., options for a SELECT property)
    val configuration: Map<String, Any?>? = null
)

/**
 * Represents the schema of an entire Lcnc Database.
 * Maps property names or IDs to their schemas.
 */
data class DatabaseSchema(
    val properties: Map<String, PropertySchema>
)

/**
 * Represents a concrete value for a specific property on a Lcnc Page.
 */
data class PropertyValue(
    val propertyId: String,
    val type: PropertyType,
    // The actual value, whose type depends on the PropertyType
    val value: Any?
)

/**
 * Represents the associative collection of all properties for a single Lcnc Page.
 */
data class PageProperties(
    // Maps property ID to the PropertyValue
    val properties: Map<String, PropertyValue>
)
