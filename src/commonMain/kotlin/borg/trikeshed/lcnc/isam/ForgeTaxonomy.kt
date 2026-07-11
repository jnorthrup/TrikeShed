package borg.trikeshed.lcnc.isam

import borg.trikeshed.lib.Series
import borg.trikeshed.lib.j

/**
 * Represents random access gems (ID-based, hierarchical structures) for the Forge taxonomy.
 * ISAM (Indexed Sequential Access Method) conceptually provides random access to these entities.
 */

/**
 * Base interface for all Forge entities that can be randomly accessed by a unique ID.
 */
interface ForgeEntity {
    val id: String
}

/**
 * Represents a discrete block of content in Forge (e.g., paragraph, heading, list item).
 * Blocks can contain children, forming a tree.
 */
data class ForgeBlock(
    override val id: String,
    val type: String, // e.g., "paragraph", "heading_1", "to_do"
    val parentId: String?,
    // In TrikeShed, hierarchies are often modeled via Series
    val children: Series<ForgeBlock>? = null,
    // Content payload can vary by block type, often associative or raw text
    val content: Any? = null
) : ForgeEntity

/**
 * Represents a Forge Page, which itself is a collection of Blocks and has associative properties.
 */
data class ForgePage(
    override val id: String,
    val title: String,
    val parentId: String?, // Could be a Workspace, Database, or another Page
    // A page is fundamentally a container of blocks
    val contentBlocks: Series<ForgeBlock>
) : ForgeEntity

/**
 * Represents a Forge Database, containing a schema and a collection of Pages (rows).
 */
data class ForgeDatabase(
    override val id: String,
    val title: String,
    val parentId: String?,
    // The entries (rows) in a database are Forge pages
    val pages: Series<ForgePage>
) : ForgeEntity

/**
 * Represents a top-level Forge Workspace containing Databases and Pages.
 */
data class ForgeWorkspace(
    override val id: String,
    val name: String,
    // Top level containers in the workspace
    val databases: Series<ForgeDatabase>,
    val pages: Series<ForgePage>
) : ForgeEntity