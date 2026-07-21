/*
 * Copyright (c) 2026 TrikeShed Authors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
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
    TITLE, 
    TEXT, 
    NUMBER, 
    SELECT, 
    CHECKBOX, 
    DATE,
    URL,
    EMAIL,
    PHONE_NUMBER
    
    // De-stubbed aspirational types (removed 2026-07-21, see git history or doc/lcnc-property-type-decision.md for more info)
    // MULTI_SELECT: removed 2026-07-21, was meant for multiple tags/selects
    // PEOPLE: removed 2026-07-21, was meant for user references
    // FILES: removed 2026-07-21, was meant for file attachments and images
    // FORMULA: removed 2026-07-21, was meant for computed column formulas
    // RELATION: removed 2026-07-21, was meant for inter-database relations
    // ROLLUP: removed 2026-07-21, was meant for aggregating related properties
    // CREATED_TIME: removed 2026-07-21, was meant for automatic creation timestamp
    // CREATED_BY: removed 2026-07-21, was meant for automatic creator tracking
    // LAST_EDITED_TIME: removed 2026-07-21, was meant for automatic modification timestamp
    // LAST_EDITED_BY: removed 2026-07-21, was meant for automatic modifier tracking
}

/**
 * Represents a reference to a user in a PEOPLE property.
 */
data class UserRef(val id: String)

/**
 * Represents a reference to a file or image in a FILES property.
 */
data class FileRef(val url: String)

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
