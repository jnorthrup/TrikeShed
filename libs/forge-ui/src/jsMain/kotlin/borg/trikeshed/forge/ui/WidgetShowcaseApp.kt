package borg.trikeshed.forge.ui

import kotlinx.browser.document
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import org.w3c.dom.HTMLElement

private const val APP_ROOT_ID = "app"

fun main() {
    val pages = widgetShowcasePages()
    val root = (document.getElementById(APP_ROOT_ID) as? HTMLElement) ?: document.body ?: return

    var selectedPageIndex = 0
    var selectedWidgetIndex = 0
    var zoom = 1.0
    var panX = 0.0
    var panY = 0.0
    var flipped = false

    fun render() {
        val page = pages[selectedPageIndex]
        root.innerHTML = buildPageMarkup(page, pages, selectedPageIndex, selectedWidgetIndex, zoom, panX, panY, flipped)
        bindEvents(page, pages) { pageIndex, widgetIndex, action ->
            selectedPageIndex = pageIndex
            selectedWidgetIndex = widgetIndex
            when (action) {
                "zoom-in" -> zoom = (zoom + 0.15).coerceAtMost(1.8)
                "zoom-out" -> zoom = (zoom - 0.15).coerceAtLeast(0.7)
                "flip" -> flipped = !flipped
                "center" -> {
                    zoom = 1.0
                    panX = 0.0
                    panY = 0.0
                }
                "pan-left" -> panX -= 24.0
                "pan-right" -> panX += 24.0
                "pan-up" -> panY -= 24.0
                "pan-down" -> panY += 24.0
            }
            render()
        }
    }

    render()
}

private fun buildPageMarkup(
    page: WidgetShowcasePage,
    pages: List<WidgetShowcasePage>,
    selectedPageIndex: Int,
    selectedWidgetIndex: Int,
    zoom: Double,
    panX: Double,
    panY: Double,
    flipped: Boolean,
): String = buildString {
    appendLine("<div class=\"forge-showcase layout-${page.layout.name.lowercase()}\">")
    appendLine("  <header class=\"showcase-header\">")
    appendLine("    <div>")
    appendLine("      <p class=\"eyebrow\">Forge UI widget showcase</p>")
    appendLine("      <h1>${page.title.escapeHtml()}</h1>")
    appendLine("      <p class=\"lede\">${page.subtitle.escapeHtml()}</p>")
    appendLine("    </div>")
    appendLine("    <div class=\"page-switcher\">")
    pages.forEachIndexed { index, candidate ->
        val active = if (index == selectedPageIndex) "active" else ""
        appendLine("      <button class=\"page-button $active\" data-page=\"$index\">${candidate.title.escapeHtml()}</button>")
    }
    appendLine("    </div>")
    appendLine("  </header>")
    appendLine("  <main class=\"showcase-layout\">")
    appendLine("    <section class=\"preview-panel\">")
    appendLine("      <div class=\"preview-header\">")
    appendLine("        <span class=\"layout-pill\">${page.layout.name.lowercase().replace('_', ' ')}</span>")
    appendLine("        <span class=\"template-pill\">separate movie export ready</span>")
    appendLine("      </div>")

    if (page.layout == WidgetShowcaseLayout.DOC) {
        appendLine("      <div class=\"doc-layout\">")
        appendLine("        <aside class=\"doc-outline\">")
        page.widgets.forEachIndexed { index, widget ->
            val active = if (index == selectedWidgetIndex) "active" else ""
            appendLine("          <button class=\"widget-card $active\" data-widget=\"$index\" style=\"--accent:${widget.accent};\">")
            appendLine("            <div class=\"widget-kicker\">${widget.kind.name.lowercase()}</div>")
            appendLine("            <div class=\"widget-title\">${widget.title.escapeHtml()}</div>")
            appendLine("            <div class=\"widget-value\">${widget.value.escapeHtml()}</div>")
            appendLine("            <p class=\"widget-body\">${widget.body.escapeHtml()}</p>")
            if (widget.badges.isNotEmpty()) {
                appendLine("            <div class=\"badge-row\">")
                widget.badges.forEach { badge ->
                    appendLine("              <span class=\"badge\">${badge.escapeHtml()}</span>")
                }
                appendLine("            </div>")
            }
            appendLine("          </button>")
        }
        appendLine("        </aside>")
        val selected = page.widgets[selectedWidgetIndex.coerceIn(page.widgets.indices)]
        appendLine("        <article class=\"doc-reading\">")
        appendLine("          <div class=\"inspector-card\">")
        appendLine("            <p class=\"eyebrow\">Intelligent document</p>")
        appendLine("            <h2>${selected.title.escapeHtml()}</h2>")
        appendLine("            <p class=\"lede\">${selected.body.escapeHtml()}</p>")
        appendLine("            <div class=\"doc-meta\">")
        appendLine("              <span class=\"layout-pill\">${selected.kind.name.lowercase()}</span>")
        appendLine("              <span class=\"template-pill\">${selected.value.escapeHtml()}</span>")
        appendLine("            </div>")
        appendLine("            <p class=\"lede\">This opening page is a living document: the outline links directly into the blackboard terrain, the board, and the radar metaphor without breaking the reading flow.</p>")
        appendLine("          </div>")
        appendLine("          <div class=\"inspector-card notes\">")
        appendLine("            <p class=\"eyebrow\">Linked sections</p>")
        selected.links.take(4).forEach { link ->
            appendLine("            <p class=\"doc-link\">→ ${link.escapeHtml()}</p>")
        }
        appendLine("          </div>")
        appendLine("          <div class=\"inspector-card notes\">")
        appendLine("            <p class=\"eyebrow\">Document notes</p>")
        page.templateNotes.forEach { note ->
            appendLine("            <p>• ${note.escapeHtml()}</p>")
        }
        appendLine("          </div>")
        appendLine("        </article>")
        appendLine("      </div>")
    } else if (page.layout == WidgetShowcaseLayout.RADAR) {
        val placements = radarPlacements(page, selectedWidgetIndex, zoom, panX, panY, flipped)
        val byId = placements.associateBy { it.widget.id }
        appendLine("      <div class=\"radar-toolbar\">")
        appendLine("        <button class=\"page-button\" data-action=\"center\">center</button>")
        appendLine("        <button class=\"page-button\" data-action=\"pan-left\">←</button>")
        appendLine("        <button class=\"page-button\" data-action=\"pan-right\">→</button>")
        appendLine("        <button class=\"page-button\" data-action=\"pan-up\">↑</button>")
        appendLine("        <button class=\"page-button\" data-action=\"pan-down\">↓</button>")
        appendLine("        <button class=\"page-button\" data-action=\"zoom-out\">zoom −</button>")
        appendLine("        <button class=\"page-button\" data-action=\"zoom-in\">zoom +</button>")
        appendLine("        <button class=\"page-button\" data-action=\"flip\">${if (flipped) "flip on" else "flip off"}</button>")
        appendLine("        <span class=\"radar-status\">zoom ${((zoom * 100).toInt() / 100.0)}x · pan ${panX.toInt()}, ${panY.toInt()}</span>")
        appendLine("      </div>")
        appendLine("      <div class=\"radar-stage\" style=\"--zoom:$zoom; --pan-x:${panX}px; --pan-y:${panY}px;\">")
        appendLine("        <svg class=\"radar-links\" viewBox=\"0 0 1000 620\" preserveAspectRatio=\"none\" aria-hidden=\"true\">")
        placements.forEach { placement ->
            placement.widget.links.forEach { targetId ->
                val target = byId[targetId] ?: return@forEach
                appendLine(
                    "          <line x1=\"${placement.centerX}\" y1=\"${placement.centerY}\" x2=\"${target.centerX}\" y2=\"${target.centerY}\" stroke=\"${placement.widget.accent}\" stroke-opacity=\"0.30\" stroke-width=\"2.5\" />"
                )
            }
        }
        appendLine("        </svg>")
        placements.forEachIndexed { index, placement ->
            val active = if (index == selectedWidgetIndex) "active" else ""
            val selected = index == selectedWidgetIndex
            val w = if (selected) 250 else 220
            val h = if (selected) 132 else 118
            val left = (placement.centerX - (w / 2.0)).toInt()
            val top = (placement.centerY - (h / 2.0)).toInt()
            appendLine("        <button class=\"radar-node $active\" data-widget=\"$index\" style=\"left:${left}px; top:${top}px; width:${w}px; min-height:${h}px; --accent:${placement.widget.accent};\">")
            appendLine("          <span class=\"node-kicker\">${placement.widget.kind.name.lowercase()}</span>")
            appendLine("          <span class=\"node-title\">${placement.widget.title.escapeHtml()}</span>")
            appendLine("          <span class=\"node-value\">${placement.widget.value.escapeHtml()}</span>")
            appendLine("          <span class=\"node-body\">${placement.widget.body.escapeHtml()}</span>")
            if (placement.widget.badges.isNotEmpty()) {
                appendLine("          <span class=\"badge-row\">")
                placement.widget.badges.take(3).forEach { badge ->
                    appendLine("            <span class=\"badge\">${badge.escapeHtml()}</span>")
                }
                appendLine("          </span>")
            }
            appendLine("        </button>")
        }
        appendLine("      </div>")
    } else {
        appendLine("      <div class=\"widget-grid widget-grid-${page.layout.name.lowercase()}\">")
        page.widgets.forEachIndexed { index, widget ->
            val active = if (index == selectedWidgetIndex) "active" else ""
            appendLine("        <button class=\"widget-card $active\" data-widget=\"$index\" style=\"--accent:${widget.accent};\">")
            appendLine("          <div class=\"widget-kicker\">${widget.kind.name.lowercase()}</div>")
            appendLine("          <div class=\"widget-title\">${widget.title.escapeHtml()}</div>")
            appendLine("          <div class=\"widget-value\">${widget.value.escapeHtml()}</div>")
            appendLine("          <p class=\"widget-body\">${widget.body.escapeHtml()}</p>")
            if (widget.badges.isNotEmpty()) {
                appendLine("          <div class=\"badge-row\">")
                widget.badges.forEach { badge ->
                    appendLine("            <span class=\"badge\">${badge.escapeHtml()}</span>")
                }
                appendLine("          </div>")
            }
            if (widget.ctas.isNotEmpty()) {
                appendLine("          <div class=\"cta-row\">")
                widget.ctas.forEach { cta ->
                    appendLine("            <span class=\"cta\">${cta.escapeHtml()}</span>")
                }
                appendLine("          </div>")
            }
            appendLine("        </button>")
        }
        appendLine("      </div>")
    }

    val widget = page.widgets.getOrNull(selectedWidgetIndex.coerceIn(page.widgets.indices)) ?: page.widgets.first()
    appendLine("    </section>")
    appendLine("    <aside class=\"inspector-panel\">")
    appendLine("      <div class=\"inspector-card\">")
    appendLine("        <p class=\"eyebrow\">Inspector</p>")
    appendLine("        <h2>${widget.title.escapeHtml()}</h2>")
    appendLine("        <p class=\"lede\">${widget.body.escapeHtml()}</p>")
    appendLine("        <dl class=\"inspector-list\">")
    appendLine("          <div><dt>kind</dt><dd>${widget.kind.name.lowercase()}</dd></div>")
    appendLine("          <div><dt>tone</dt><dd>${widget.tone.name.lowercase()}</dd></div>")
    appendLine("          <div><dt>accent</dt><dd>${widget.accent.escapeHtml()}</dd></div>")
    appendLine("          <div><dt>layout</dt><dd>${page.layout.name.lowercase()}</dd></div>")
    if (widget.links.isNotEmpty()) {
        appendLine("          <div><dt>links</dt><dd>${widget.links.joinToString(" · ").escapeHtml()}</dd></div>")
    }
    appendLine("        </dl>")
    appendLine("      </div>")
    appendLine("      <div class=\"inspector-card notes\">")
    appendLine("        <p class=\"eyebrow\">Template notes</p>")
    page.templateNotes.forEach { note ->
        appendLine("        <p>• ${note.escapeHtml()}</p>")
    }
    appendLine("      </div>")
    appendLine("    </aside>")
    appendLine("  </main>")
    appendLine("</div>")
}

private fun bindEvents(
    page: WidgetShowcasePage,
    pages: List<WidgetShowcasePage>,
    onSelect: (Int, Int, String?) -> Unit,
) {
    pages.forEachIndexed { index, _ ->
        document.querySelector("[data-page='$index']")?.addEventListener("click", { _ ->
            onSelect(index, 0, null)
        })
    }
    page.widgets.forEachIndexed { index, _ ->
        document.querySelector("[data-widget='$index']")?.addEventListener("click", { _ ->
            onSelect(pages.indexOf(page), index, null)
        })
    }
    listOf("zoom-in", "zoom-out", "flip", "center", "pan-left", "pan-right", "pan-up", "pan-down").forEach { action ->
        document.querySelector("[data-action='$action']")?.addEventListener("click", { _ ->
            onSelect(pages.indexOf(page), -1, action)
        })
    }
}

private data class RadarPlacement(
    val widget: WidgetShowcaseWidget,
    val centerX: Double,
    val centerY: Double,
)

private fun radarPlacements(
    page: WidgetShowcasePage,
    selectedIndex: Int,
    zoom: Double,
    panX: Double,
    panY: Double,
    flipped: Boolean,
): List<RadarPlacement> {
    val width = 1000.0
    val height = 620.0
    val cx = width / 2.0 + panX
    val cy = height / 2.0 + panY
    val ringRadius = min(width, height) * (0.24 + (0.06 * zoom))
    val selectedIndexSafe = selectedIndex.coerceIn(page.widgets.indices)
    val selected = page.widgets[selectedIndexSafe]
    val others = page.widgets.withIndex().filter { it.index != selectedIndexSafe }
    val step = (2.0 * PI / maxOf(1, others.size))
    val placements = mutableListOf<RadarPlacement>()
    placements += RadarPlacement(selected, cx, cy)
    others.forEachIndexed { ordinal, entry ->
        val angle = -PI / 2.0 + ordinal * step
        var x = cx + cos(angle) * ringRadius * (1.0 + entry.value.links.size * 0.04)
        val y = cy + sin(angle) * ringRadius * (1.0 + entry.value.links.size * 0.04)
        if (flipped) x = width - x
        placements += RadarPlacement(entry.value, x, y)
    }
    return placements
}

private fun String.escapeHtml(): String = buildString(length) {
    for (ch in this@escapeHtml) {
        when (ch) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\'' -> append("&#39;")
            else -> append(ch)
        }
    }
}
