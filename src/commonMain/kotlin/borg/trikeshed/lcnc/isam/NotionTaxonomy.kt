package borg.trikeshed.lcnc.isam

import borg.trikeshed.lib.Series
import borg.trikeshed.lib.j

/**
 * Represents random access gems (ID-based, hierarchical structures) for the Notion taxonomy.
 * ISAM (Indexed Sequential Access Method) conceptually provides random access to these entities.
 */

/**
 * Base interface for all Notion entities that can be randomly accessed by a unique ID.
 */
interface NotionEntity {
    val id: String
}

/**
 * Represents a discrete block of content in Notion (e.g., paragraph, heading, list item).
 * Blocks can contain children, forming a tree.
 */
data class NotionBlock(
    override val id: String,
    val type: String, // e.g., "paragraph", "heading_1", "to_do"
    val parentId: String?,
    // In TrikeShed, hierarchies are often modeled via Series
    val children: Series<NotionBlock>? = null,
    // Content payload can vary by block type, often associative or raw text
    val content: Any? = null
) : NotionEntity

/**
 * Represents a Notion Page, which itself is a collection of Blocks and has associative properties.
 */
data class NotionPage(
    override val id: String,
    val title: String,
    val parentId: String?, // Could be a Workspace, Database, or another Page
    // A page is fundamentally a container of blocks
    val contentBlocks: Series<NotionBlock>
) : NotionEntity

/**
 * Represents a Notion Database, containing a schema and a collection of Pages (rows).
 */
data class NotionDatabase(
    override val id: String,
    val title: String,
    val parentId: String?,
    // The entries (rows) in a database are Notion pages
    val pages: Series<NotionPage>
) : NotionEntity

/**
 * Represents a top-level Notion Workspace containing Databases and Pages.
 */
data class NotionWorkspace(
    override val id: String,
    val name: String,
    // Top level containers in the workspace
    val databases: Series<NotionDatabase>,
    val pages: Series<NotionPage>
) : NotionEntity
