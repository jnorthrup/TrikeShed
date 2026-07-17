package borg.trikeshed.forge.gallery

import borg.trikeshed.parse.json.JsonSupport
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonConfiguration

/**
 * Browser-side gallery renderer. Called from the Forge workspace shell when
 * the user opens the gallery section. The catalog lives in commonMain so the
 * same data drives the JVM printer and the browser section.
 */
object ForgeGalleryRenderer {

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    /**
     * Render the catalog as a portable JSON string suitable for embedding in
     * the workspace seed or fetching via a dedicated endpoint.
     */
    fun renderJson(): String = JsonSupport.stringify(ForgeGalleryCatalog.toJsonValue())

    /**
     * Render the gallery as an HTML string. This is the "kitchen sink" view
     * that the browser shell mounts into #gallery-root.
     */
    fun renderHtml(): String {
        val spec = ForgeGalleryCatalog.toJsonValue()
        val widgets = (spec["widgets"] as? List<Any>) ?: emptyList()
        val sections = widgets.groupBy { (it as Map<String, Any>)["section"] as String }

        return buildString {
            append("<div class=\"gallery-root\" style=\"padding:16px;\">")
            append("<style>")
            append("""
                .gallery-root { font-family: Inter, system-ui, sans-serif; color: #dbe7f3; background: #090d13; }
                .gallery-section { margin-bottom: 24px; }
                .gallery-section h3 { margin: 0 0 12px; color: #7dcfff; font-size: 13px; text-transform: uppercase; letter-spacing: 0.1em; }
                .gallery-list { display: grid; grid-template-columns: repeat(auto-fill, minmax(240px, 1fr)); gap: 12px; }
                .gallery-card { padding: 16px; border: 1px solid #1b2635; border-radius: 12px; background: linear-gradient(180deg, rgba(18,24,36,.97), rgba(12,18,28,.95)); transition: border-color 120ms, box-shadow 120ms; }
                .gallery-card:hover { border-color: rgba(122,162,247,.45); box-shadow: 0 0 0 1px rgba(122,162,247,.12); }
                .gallery-card .name { font-weight: 700; font-size: 15px; margin-bottom: 6px; color: #dbe7f3; }
                .gallery-card .synopsis { font-size: 12px; color: #7e8da0; margin-bottom: 8px; }
                .gallery-card .id { font-size: 11px; color: #7aa2f7; font-family: ui-monospace, SFMono-Regular, Menlo, monospace; margin-bottom: 8px; }
                .gallery-card .meta { font-size: 10px; color: #7e8da0; line-height: 1.5; }
                .gallery-card .meta .targets { color: #e0af68; }
                .gallery-card .meta .preview { color: #7dcfff; }
            """)
            append("</style>")

            sections.toList().sortedBy { it.key }.forEach { entry: Map.Entry<String, List<Any>> ->
                val sectionName = entry.key
                val widgets = entry.value
                append("<section class=\"gallery-section\">")
                append("<h3>$sectionName</h3>")
                append("<div class=\"gallery-list\">")
                widgets.forEach { widget ->
                    val widgetMap = widget as Map<String, Any>
                    val id = widgetMap["id"] as String
                    val name = widgetMap["name"] as String
                    val synopsis = widgetMap["synopsis"] as? String ?: ""
                    val previewToken = widgetMap["previewToken"] as String
                    val supportTargets = (widgetMap["supportTargets"] as? List<String>)?.joinToString(", ") ?: ""
                    val apiSignature = widgetMap["apiSignature"] as? String

                    append("<article class=\"gallery-card\">")
                    append("<div class=\"name\">$name</div>")
                    append("<div class=\"synopsis\">$synopsis</div>")
                    append("<div class=\"id\">$id</div>")
                    append("<div class=\"meta\">")
                    append("<span class=\"targets\">Targets: $supportTargets</span><br/>")
                    append("<span class=\"preview\">Preview: $previewToken</span>")
                    if (apiSignature != null) {
                        append("<br/><code style=\"font-size:9px; color:#7e8da0;\">$apiSignature</code>")
                    }
                    append("</div>")
                    append("</article>")
                }
                append("</div>")
                append("</section>")
            }
            append("</div>")
        }
    }
}