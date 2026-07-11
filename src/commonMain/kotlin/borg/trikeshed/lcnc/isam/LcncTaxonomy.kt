package borg.trikeshed.lcnc.isam

import borg.trikeshed.lib.Series
import borg.trikeshed.lib.j

/**
 * Represents random access gems (ID-based, hierarchical structures) for the Lcnc taxonomy.
 * ISAM (Indexed Sequential Access Method) conceptually provides random access to these entities.
 */

/**
 * Base interface for all Lcnc entities that can be randomly accessed by a unique ID.
 */
interface LcncEntity {
    val id: String
}

/**
 * Represents a discrete block of content in Lcnc (e.g., paragraph, heading, list item).
 * Blocks can contain children, forming a tree.
 */
data class LcncBlock(
    override val id: String,
    val type: String, // e.g., "paragraph", "heading_1", "to_do"
    val parentId: String?,
    // In TrikeShed, hierarchies are often modeled via Series
    val children: Series<LcncBlock>? = null,
    // Content payload can vary by block type, often associative or raw text
    val content: Any? = null
) : LcncEntity

/**
 * Represents a Lcnc Page, which itself is a collection of Blocks and has associative properties.
 */
data class LcncPage(
    override val id: String,
    val title: String,
    val parentId: String?, // Could be a Workspace, Database, or another Page
    // A page is fundamentally a container of blocks
    val contentBlocks: Series<LcncBlock>
) : LcncEntity

/**
 * Represents a Lcnc Database, containing a schema and a collection of Pages (rows).
 */
data class LcncDatabase(
    override val id: String,
    val title: String,
    val parentId: String?,
    // The entries (rows) in a database are Lcnc pages
    val pages: Series<LcncPage>
) : LcncEntity

/**
 * Represents a top-level Lcnc Workspace containing Databases and Pages.
 */
data class LcncWorkspace(
    override val id: String,
    val name: String,
    // Top level containers in the workspace
    val databases: Series<LcncDatabase>,
    val pages: Series<LcncPage>
) : LcncEntity
