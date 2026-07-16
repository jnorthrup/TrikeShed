package borg.trikeshed.forge.gallery

import borg.trikeshed.parse.json.JsonSupport
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import borg.trikeshed.forge.blackboard.ForgeBlackboardView

/**
 * Widget category — the gallery groups every previsualizable widget into one of these
 * families.  This intentionally mirrors the categories already in use across the
 * forge-ui surfaces so the gallery can be a section of the same blackboard.
 */
@Serializable
enum class ForgeGallerySection {
    LAYOUT, INPUT, DISPLAY, FEEDBACK, DATA, CANVAS,
    FORGE, KANBAN, CONFIX, COUCH, CAS,
}

/**
 * Per-target preview grade — what the kitchen-sink / gallery can actually render
 * today for a given widget on a given host.
 *
 * FULL    – rendered with the same renderer the workspace uses (browser = DOM+SVG, JVM = ASCII/text grid).
 * DOM     – rendered directly with HTML/SVG primitives, available only in browser.
 * TEXT    – rendered as a fixed-width text grid, available on JVM (and any terminal target).
 * STUB    – described but not previewable yet (calls out to the support matrix).
 */
@Serializable
enum class ForgeGalleryPreview {
    FULL, DOM, TEXT, STUB,
}

/**
 * A single widget entry in the kitchen-sink gallery.  Pinned at commonMain so the
 * support matrix renders identically in the browser blackboard and on the JVM
 * command line.  No UI toolkit dependency: the renderer turns the description +
 * previewToken into a DOM element or a text grid.
 */
@Serializable
data class ForgeGalleryWidget(
    val id: String,                   // e.g. "layout.box", "input.button"
    val section: ForgeGallerySection,
    val name: String,                 // "Box", "Button"
    val synopsis: String,             // one-line description for the side panel
    val supportTargets: Set<String>,  // ["JVM_DESKTOP","JS_BROWSER","WASM_JS_BROWSER",...]
    val previewToken: String,         // renderer key — see ForgeGalleryRenderer
    val apiSignature: String? = null, // Composable signature if available
)

/**
 * The full gallery catalog.  Lives in commonMain; the JVM printer and the
 * browser section both consume it.  Keep additions ordered so the matrix
 * renders deterministically across targets.
 */
object ForgeGalleryCatalog {

    private val widgets: List<ForgeGalleryWidget> = listOf(
        // ── LAYOUT ──
        layout("layout.box", "Box",
            synopsis = "Stack children with alignment + propagation.",
            previewToken = "box-stack",
            supportTargets = setOf("JVM_DESKTOP", "JS_BROWSER", "JS_NODE", "WASM_JS_BROWSER", "WASM_JS_NODE", "MACOS_ARM64", "LINUX_X64", "WINDOWS_X64", "IOS_ARM64", "ANDROID_ARM64"),
            apiSignature = "@Composable fun Box(modifier: Modifier, content: @Composable BoxScope.() -> Unit)",
        ),
        layout("layout.row", "Row",
            synopsis = "Horizontal stack with weighted children.",
            previewToken = "row-weights",
            supportTargets = setOf("JVM_DESKTOP", "JS_BROWSER", "JS_NODE", "WASM_JS_BROWSER", "WASM_JS_NODE", "MACOS_ARM64", "LINUX_X64", "WINDOWS_X64", "IOS_ARM64", "ANDROID_ARM64"),
            apiSignature = "@Composable fun Row(modifier: Modifier, content: @Composable RowScope.() -> Unit)",
        ),
        layout("layout.column", "Column",
            synopsis = "Vertical stack with weighted children.",
            previewToken = "column-weights",
            supportTargets = setOf("JVM_DESKTOP", "JS_BROWSER", "JS_NODE", "WASM_JS_BROWSER", "WASM_JS_NODE", "MACOS_ARM64", "LINUX_X64", "WINDOWS_X64", "IOS_ARM64", "ANDROID_ARM64"),
            apiSignature = "@Composable fun Column(modifier: Modifier, content: @Composable ColumnScope.() -> Unit)",
        ),
        layout("layout.flow", "Flow",
            synopsis = "Wrap to next line when bounds are exceeded.",
            previewToken = "flow-wrap",
            supportTargets = setOf("JVM_DESKTOP", "JS_BROWSER", "JS_NODE", "WASM_JS_BROWSER", "WASM_JS_NODE", "MACOS_ARM64", "LINUX_X64", "WINDOWS_X64", "IOS_ARM64", "ANDROID_ARM64"),
        ),

        // ── INPUT ──
        input("input.button", "Button",
            synopsis = "Tappable surface with primary/secondary/tertiary variants.",
            previewToken = "button-tap",
            supportTargets = setOf("JVM_DESKTOP", "JS_BROWSER", "WASM_JS_BROWSER", "MACOS_ARM64", "LINUX_X64", "WINDOWS_X64", "IOS_ARM64", "ANDROID_ARM64"),
            apiSignature = "@Composable fun Button(onClick: () -> Unit, content: @Composable RowScope.() -> Unit)",
        ),
        input("input.textfield", "TextField",
            synopsis = "Single-line text editor with placeholder, error and trailing icon.",
            previewToken = "textfield-edit",
            supportTargets = setOf("JVM_DESKTOP", "JS_BROWSER", "WASM_JS_BROWSER", "MACOS_ARM64", "LINUX_X64", "WINDOWS_X64", "IOS_ARM64", "ANDROID_ARM64"),
        ),
        input("input.checkbox", "Checkbox",
            synopsis = "Boolean toggle with tri-state support.",
            previewToken = "checkbox-toggle",
            supportTargets = setOf("JVM_DESKTOP", "JS_BROWSER", "WASM_JS_BROWSER", "MACOS_ARM64", "LINUX_X64", "WINDOWS_X64", "IOS_ARM64", "ANDROID_ARM64"),
        ),
        input("input.slider", "Slider",
            synopsis = "Continuous value selection with optional step.",
            previewToken = "slider-drag",
            supportTargets = setOf("JVM_DESKTOP", "JS_BROWSER", "WASM_JS_BROWSER", "MACOS_ARM64", "LINUX_X64", "WINDOWS_X64", "IOS_ARM64", "ANDROID_ARM64"),
        ),

        // ── DISPLAY ──
        display("display.text", "Text",
            synopsis = "Rich text with overflow, alignment and style spans.",
            previewToken = "text-truncate",
            supportTargets = setOf("JVM_DESKTOP", "JS_BROWSER", "JS_NODE", "WASM_JS_BROWSER", "WASM_JS_NODE", "MACOS_ARM64", "LINUX_X64", "WINDOWS_X64", "IOS_ARM64", "ANDROID_ARM64"),
        ),
        display("display.badge", "Badge",
            synopsis = "Compact count or status pill.",
            previewToken = "badge-pill",
            supportTargets = setOf("JVM_DESKTOP", "JS_BROWSER", "JS_NODE", "WASM_JS_BROWSER", "WASM_JS_NODE", "MACOS_ARM64", "LINUX_X64", "WINDOWS_X64", "IOS_ARM64", "ANDROID_ARM64"),
        ),

        // ── FEEDBACK ──
        feedback("feedback.dialog", "Dialog",
            synopsis = "Modal with confirm/dismiss actions.",
            previewToken = "dialog-modal",
            supportTargets = setOf("JVM_DESKTOP", "JS_BROWSER", "WASM_JS_BROWSER", "MACOS_ARM64", "LINUX_X64", "WINDOWS_X64", "IOS_ARM64", "ANDROID_ARM64"),
        ),
        feedback("feedback.toast", "Toast",
            synopsis = "Transient bottom-of-screen notification.",
            previewToken = "toast-pop",
            supportTargets = setOf("JVM_DESKTOP", "JS_BROWSER", "WASM_JS_BROWSER", "MACOS_ARM64", "LINUX_X64", "WINDOWS_X64", "IOS_ARM64", "ANDROID_ARM64"),
        ),

        // ── DATA ──
        data("data.list", "List",
            synopsis = "Virtualised scrollable list with selection.",
            previewToken = "list-rows",
            supportTargets = setOf("JVM_DESKTOP", "JS_BROWSER", "WASM_JS_BROWSER", "MACOS_ARM64", "LINUX_X64", "WINDOWS_X64", "IOS_ARM64", "ANDROID_ARM64"),
        ),

        // ── CANVAS ──
        canvas("canvas.skiko", "Skiko Canvas",
            synopsis = "Imperative drawing — circles, paths, gradients.",
            previewToken = "skiko-rings",
            supportTargets = setOf("JVM_DESKTOP", "JS_BROWSER", "JS_NODE", "WASM_JS_BROWSER", "WASM_JS_NODE", "MACOS_ARM64", "LINUX_X64", "WINDOWS_X64", "IOS_ARM64", "ANDROID_ARM64"),
        ),

        // ── FORGE — the workspace itself, broken into re-usable surfaces ──
        forge("forge.board", "Forge Board",
            synopsis = "Animated board lanes with selection, zoom, and checklist fractal.",
            previewToken = "forge-board-lanes",
            supportTargets = setOf("JVM_DESKTOP", "JS_BROWSER", "JS_NODE", "WASM_JS_BROWSER", "WASM_JS_NODE"),
            apiSignature = "object ForgeBoardFSM / class KanbanBoard",
        ),
        forge("forge.reactor", "Reactor Strip",
            synopsis = "Live status chips + recent signal taxonomy with causality trail.",
            previewToken = "forge-reactor-strip",
            supportTargets = setOf("JVM_DESKTOP", "JS_BROWSER", "JS_NODE", "WASM_JS_BROWSER", "WASM_JS_NODE"),
        ),
        forge("forge.spatial", "Spatial Zoom",
            synopsis = "RTS zoom from workspace lanes into card-level checklist orbit.",
            previewToken = "forge-spatial-zoom",
            supportTargets = setOf("JVM_DESKTOP", "JS_BROWSER", "WASM_JS_BROWSER"),
        ),
        forge("forge.graph", "Causal Graph",
            synopsis = "Force-directed causal DAG; drag, zoom, click-to-inspect.",
            previewToken = "forge-causal-graph",
            supportTargets = setOf("JVM_DESKTOP", "JS_BROWSER", "WASM_JS_BROWSER"),
        ),

        // ── KANBAN — the typed card / column / WIP primitives ──
        kanban("kanban.card", "Card",
            synopsis = "Card with priority, status, checklist, attachments.",
            previewToken = "kanban-card",
            supportTargets = setOf("JVM_DESKTOP", "JS_BROWSER", "JS_NODE", "WASM_JS_BROWSER", "WASM_JS_NODE"),
        ),
        kanban("kanban.column", "Column",
            synopsis = "Ordered lane with WIP limit and ordered card placement.",
            previewToken = "kanban-column",
            supportTargets = setOf("JVM_DESKTOP", "JS_BROWSER", "JS_NODE", "WASM_JS_BROWSER", "WASM_JS_NODE"),
        ),
        kanban("kanban.wip", "WIP Limit",
            synopsis = "Visual + state enforcement when lane exceeds capacity.",
            previewToken = "kanban-wip",
            supportTargets = setOf("JVM_DESKTOP", "JS_BROWSER", "WASM_JS_BROWSER"),
        ),

        // ── CONFIX — type-safe config oracle + ingest pipeline ──
        confix("confix.doc", "ConfixDoc",
            synopsis = "Type-safe config oracle with cursor navigation.",
            previewToken = "confix-doc-tree",
            supportTargets = setOf("JVM_DESKTOP", "JS_BROWSER", "JS_NODE", "WASM_JS_BROWSER", "WASM_JS_NODE"),
            apiSignature = "class ConfixDoc / object JsonParser",
        ),
        confix("confix.cursor", "ConfixCursor",
            synopsis = "Lazy projection with column metadata and reduction facets.",
            previewToken = "confix-cursor-table",
            supportTargets = setOf("JVM_DESKTOP", "JS_BROWSER", "JS_NODE", "WASM_JS_BROWSER", "WASM_JS_NODE"),
        ),
        confix("confix.facet", "FacetedCursor",
            synopsis = "HOT / COLD / IMMUTABLE / COMPUTED / INDEXED / EPHEMERAL / WAL_ACTIVE facets.",
            previewToken = "confix-facet-mask",
            supportTargets = setOf("JVM_DESKTOP", "JS_BROWSER", "JS_NODE", "WASM_JS_BROWSER", "WASM_JS_NODE"),
        ),
        confix("confix.ingest", "Markdown Ingest",
            synopsis = "Parse /tmp/hi markdown → Rete facts + causal nodes + Kanban cards.",
            previewToken = "confix-ingest-pipeline",
            supportTargets = setOf("JVM_DESKTOP", "JS_NODE", "WASM_JS_NODE"),
            apiSignature = "object ForgeKanbanIngest.persistMarkdown(userId, path)",
        ),

        // ── COUCH — content store + view-server + cascade rollups ──
        couch("couch.docstore", "ConfixDocStore",
            synopsis = "Content-addressed document store; by-id index.",
            previewToken = "couch-docstore",
            supportTargets = setOf("JVM_DESKTOP", "JS_BROWSER", "JS_NODE", "WASM_JS_BROWSER", "WASM_JS_NODE"),
        ),
        couch("couch.viewserver", "ViewServer",
            synopsis = "Reducer composition: cascade / by-org / by-machine rollups.",
            previewToken = "couch-viewserver",
            supportTargets = setOf("JVM_DESKTOP", "JS_NODE", "WASM_JS_NODE"),
        ),
        couch("couch.cascade", "Cascade Rollup",
            synopsis = "tool:couchdbcascade view → metric rollup rows.",
            previewToken = "couch-cascade-grid",
            supportTargets = setOf("JVM_DESKTOP", "JS_BROWSER", "JS_NODE", "WASM_JS_BROWSER", "WASM_JS_NODE"),
        ),

        // ── CAS — content-addressable blob storage + attachments ──
        cas("cas.blob", "Blob CAS",
            synopsis = "SHA-256 → path blob store; digest dedup; indexed attachments.",
            previewToken = "cas-blob-grid",
            supportTargets = setOf("JVM_DESKTOP", "JS_BROWSER", "JS_NODE", "WASM_JS_BROWSER", "WASM_JS_NODE"),
            apiSignature = "class IndexedAttachmentStore / fun put(bytes): ContentId",
        ),
        cas("cas.dedup", "Content Dedup",
            synopsis = "Identical blobs share a digest; reference-counted deletion.",
            previewToken = "cas-dedup-graph",
            supportTargets = setOf("JVM_DESKTOP", "JS_NODE", "WASM_JS_NODE"),
        ),
        cas("cas.attachment", "Doc Attachment",
            synopsis = "PouchDB-style doc._attachments → Confix CBOR record + blob bytes.",
            previewToken = "cas-attachment-row",
            supportTargets = setOf("JVM_DESKTOP", "JS_BROWSER", "JS_NODE", "WASM_JS_BROWSER", "WASM_JS_NODE"),
        ),
    )

    fun widgets(): List<ForgeGalleryWidget> = widgets

    fun bySection(section: ForgeGallerySection): List<ForgeGalleryWidget> =
        widgets.filter { it.section == section }

    fun find(id: String): ForgeGalleryWidget? = widgets.firstOrNull { it.id == id }

    /**
     * Encode the catalog as a portable map suitable for embedding in the
     * workspace seed JSON.  Keys are stable strings so the JS renderer never
     * depends on Kotlin reflection.
     */
    fun toJsonValue(): Map<String, Any?> = mapOf(
        "version" to CATALOG_VERSION,
        "sections" to ForgeGallerySection.values().map { it.name },
        "widgets" to widgets.map { widget ->
            mapOf<String, Any?>(
                "id" to widget.id,
                "section" to widget.section.name,
                "name" to widget.name,
                "synopsis" to widget.synopsis,
                "previewToken" to widget.previewToken,
                "supportTargets" to widget.supportTargets.toList(),
                "apiSignature" to widget.apiSignature,
            )
        },
    )

    fun renderJson(): String = JsonSupport.stringify(toJsonValue())

    /** Bumped when widget rows are added or removed — gate downstream caches. */
    const val CATALOG_VERSION: String = "forge-gallery-v2"

    private fun layout(
        id: String,
        name: String,
        synopsis: String,
        previewToken: String,
        supportTargets: Set<String>,
        apiSignature: String? = null,
    ): ForgeGalleryWidget = ForgeGalleryWidget(
        id = id,
        section = ForgeGallerySection.LAYOUT,
        name = name,
        synopsis = synopsis,
        supportTargets = supportTargets,
        previewToken = previewToken,
        apiSignature = apiSignature,
    )

    private fun input(
        id: String,
        name: String,
        synopsis: String,
        previewToken: String,
        supportTargets: Set<String>,
        apiSignature: String? = null,
    ): ForgeGalleryWidget = ForgeGalleryWidget(
        id = id,
        section = ForgeGallerySection.INPUT,
        name = name,
        synopsis = synopsis,
        supportTargets = supportTargets,
        previewToken = previewToken,
        apiSignature = apiSignature,
    )

    private fun display(
        id: String,
        name: String,
        synopsis: String,
        previewToken: String,
        supportTargets: Set<String>,
        apiSignature: String? = null,
    ): ForgeGalleryWidget = ForgeGalleryWidget(
        id = id,
        section = ForgeGallerySection.DISPLAY,
        name = name,
        synopsis = synopsis,
        supportTargets = supportTargets,
        previewToken = previewToken,
        apiSignature = apiSignature,
    )

    private fun feedback(
        id: String,
        name: String,
        synopsis: String,
        previewToken: String,
        supportTargets: Set<String>,
        apiSignature: String? = null,
    ): ForgeGalleryWidget = ForgeGalleryWidget(
        id = id,
        section = ForgeGallerySection.FEEDBACK,
        name = name,
        synopsis = synopsis,
        supportTargets = supportTargets,
        previewToken = previewToken,
        apiSignature = apiSignature,
    )

    private fun data(
        id: String,
        name: String,
        synopsis: String,
        previewToken: String,
        supportTargets: Set<String>,
        apiSignature: String? = null,
    ): ForgeGalleryWidget = ForgeGalleryWidget(
        id = id,
        section = ForgeGallerySection.DATA,
        name = name,
        synopsis = synopsis,
        supportTargets = supportTargets,
        previewToken = previewToken,
        apiSignature = apiSignature,
    )

    private fun canvas(
        id: String,
        name: String,
        synopsis: String,
        previewToken: String,
        supportTargets: Set<String>,
        apiSignature: String? = null,
    ): ForgeGalleryWidget = ForgeGalleryWidget(
        id = id,
        section = ForgeGallerySection.CANVAS,
        name = name,
        synopsis = synopsis,
        supportTargets = supportTargets,
        previewToken = previewToken,
        apiSignature = apiSignature,
    )

    private fun forge(
        id: String,
        name: String,
        synopsis: String,
        previewToken: String,
        supportTargets: Set<String>,
        apiSignature: String? = null,
    ): ForgeGalleryWidget = ForgeGalleryWidget(
        id = id,
        section = ForgeGallerySection.FORGE,
        name = name,
        synopsis = synopsis,
        supportTargets = supportTargets,
        previewToken = previewToken,
        apiSignature = apiSignature,
    )

    private fun kanban(
        id: String,
        name: String,
        synopsis: String,
        previewToken: String,
        supportTargets: Set<String>,
        apiSignature: String? = null,
    ): ForgeGalleryWidget = ForgeGalleryWidget(
        id = id,
        section = ForgeGallerySection.KANBAN,
        name = name,
        synopsis = synopsis,
        supportTargets = supportTargets,
        previewToken = previewToken,
        apiSignature = apiSignature,
    )

    private fun confix(
        id: String,
        name: String,
        synopsis: String,
        previewToken: String,
        supportTargets: Set<String>,
        apiSignature: String? = null,
    ): ForgeGalleryWidget = ForgeGalleryWidget(
        id = id,
        section = ForgeGallerySection.CONFIX,
        name = name,
        synopsis = synopsis,
        supportTargets = supportTargets,
        previewToken = previewToken,
        apiSignature = apiSignature,
    )

    private fun couch(
        id: String,
        name: String,
        synopsis: String,
        previewToken: String,
        supportTargets: Set<String>,
        apiSignature: String? = null,
    ): ForgeGalleryWidget = ForgeGalleryWidget(
        id = id,
        section = ForgeGallerySection.COUCH,
        name = name,
        synopsis = synopsis,
        supportTargets = supportTargets,
        previewToken = previewToken,
        apiSignature = apiSignature,
    )

    private fun cas(
        id: String,
        name: String,
        synopsis: String,
        previewToken: String,
        supportTargets: Set<String>,
        apiSignature: String? = null,
    ): ForgeGalleryWidget = ForgeGalleryWidget(
        id = id,
        section = ForgeGallerySection.CAS,
        name = name,
        synopsis = synopsis,
        supportTargets = supportTargets,
        previewToken = previewToken,
        apiSignature = apiSignature,
    )
}