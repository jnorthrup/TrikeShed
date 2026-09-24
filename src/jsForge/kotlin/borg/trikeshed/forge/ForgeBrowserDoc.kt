@file:Suppress("UNCHECKED_CAST")

package borg.trikeshed.forge

import borg.trikeshed.forge.doc.BlockType
import borg.trikeshed.forge.doc.ForgeBlock
import borg.trikeshed.forge.doc.blocksToHtml
import borg.trikeshed.forge.doc.blocksToText
import borg.trikeshed.forge.doc.enterContinuation
import borg.trikeshed.forge.doc.exportFileStem
import borg.trikeshed.forge.doc.importedPageOf
import borg.trikeshed.forge.doc.numberedIndex
import borg.trikeshed.forge.doc.pageFormatLabel
import borg.trikeshed.forge.doc.slashMatches
import kotlinx.browser.document
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.events.KeyboardEvent

/**
 * Document editor + sidebar + slash menu — the jsForge DOM half of script.js sections
 * "Render: sidebar", "Render: document/block editor", and "Slash menu".
 */

// ── Render: sidebar ─────────────────────────────────────────────────────

fun ForgeBrowser.renderSidebar() {
    val pageTreeEl = el("page-tree") ?: return
    pageTreeEl.clearChildren()
    for (page in workspace.pages) {
        val item = document.createElement("div") as HTMLElement
        val isActive = page.id == workspace.activePageId
        item.className = "page-tree-item" + (if (isActive) " active" else "")
        item.setAttribute("role", "button")
        item.tabIndex = 0
        item.setAttribute("aria-label", (if (isActive) "Active page: " else "Page: ") + page.title.ifEmpty { "Untitled" })
        if (isActive) item.setAttribute("aria-current", "page")
        val toggle = el("span", "tree-toggle", if (page.children.isNotEmpty()) "▾" else "▸")
        toggle.setAttribute("aria-hidden", "true")
        val icon = el("span", "tree-icon", page.icon.ifEmpty { "▤" })
        icon.setAttribute("aria-hidden", "true")
        val label = el("span", "tree-label" + (if (page.title.isEmpty()) " untitled" else ""), page.title.ifEmpty { "Untitled" })
        item.append(toggle, icon, label)
        item.addEventListener("click", {
            mutate { s -> s.activePageId = page.id }
            renderAll()
        })
        pageTreeEl.appendChild(item)
    }
}

// ── Render: document ────────────────────────────────────────────────────

fun ForgeBrowser.renderTitle() {
    val page = workspace.activePage()
    val titleEl = el("doc-title")
    if (titleEl != null && titleEl.textContent != page.title) titleEl.textContent = page.title
    el("doc-icon")?.textContent = page.icon.ifEmpty { "▤" }
    el("breadcrumb")?.textContent = "Private  /  " + page.title.ifEmpty { "Untitled" }
    renderDocTools()
}

fun ForgeBrowser.wireTitle() {
    val titleEl = el("doc-title") ?: return
    titleEl.addEventListener("input", {
        mutate { workspace.activePage().title = titleEl.textContent ?: "" }
        renderSidebar()
        el("breadcrumb")?.textContent = "Private  /  " + workspace.activePage().title.ifEmpty { "Untitled" }
    })
    titleEl.addEventListener("keydown", { e ->
        if ((e as KeyboardEvent).key == "Enter") {
            e.preventDefault()
            val page = workspace.activePage()
            if (page.blocks.isEmpty()) {
                mutate { page.blocks.add(ForgeBlock(forgeUid(), BlockType.P, "")) }
                renderBlocks()
                focusBlock(page.blocks[0].id, atStart = true)
            } else {
                focusBlock(page.blocks[0].id, atStart = false)
            }
        }
    })
}

fun ForgeBrowser.renderBlocks() {
    val blocksEl = el("doc-blocks") ?: return
    val page = workspace.activePage()
    blocksEl.clearChildren()
    page.blocks.forEachIndexed { idx, block -> blocksEl.appendChild(blockEl(block, idx)) }
}

fun ForgeBrowser.renderDocTools() {
    val docToolsEl = el("doc-tools") ?: return
    docToolsEl.clearChildren()
    val page = workspace.activePage()
    docToolsEl.appendChild(el("span", "doc-format", pageFormatLabel(page.format)))
    docToolsEl.appendChild(workbookButton("Export text", "Download this document as plain text") {
        downloadText(exportFileStem(page.title, "document") + ".txt", blocksToText(page))
    })
    docToolsEl.appendChild(workbookButton("Export HTML", "Download an editable HTML document") {
        downloadText(exportFileStem(page.title, "document") + ".html", blocksToHtml(page), "text/html;charset=utf-8")
    })
    docToolsEl.appendChild(workbookButton("Print / PDF", "Print or save this document as PDF") {
        js("window.print()")
    })
}

fun ForgeBrowser.blockEl(block: ForgeBlock, idx: Int): HTMLElement {
    val el = document.createElement("div") as HTMLElement
    el.className = "block block-" + block.type.id
    if (block.type == BlockType.TODO && block.checked) el.classList.add("done")
    el.asDynamic().dataset["blockId"] = block.id

    // gutter: + and drag handle
    val gutter = el("div", "block-gutter")
    val addBtn = el("button", "gutter-btn", "+")
    addBtn.title = "Add block below"
    addBtn.setAttribute("aria-label", "Add block below")
    addBtn.addEventListener("click", {
        insertBlock(idx + 1, ForgeBlock(forgeUid(), BlockType.P, ""))
        focusBlock(workspace.activePage().blocks[idx + 1].id, atStart = true)
    })
    val dragBtn = el("button", "gutter-btn gutter-drag", "⋮⋮")
    dragBtn.title = "Drag to reorder"
    dragBtn.setAttribute("aria-label", "Drag to reorder block")
    gutter.append(addBtn, dragBtn)
    el.appendChild(gutter)

    if (block.type == BlockType.DIVIDER) {
        el.appendChild(document.createElement("hr"))
        return el
    }

    if (block.type == BlockType.TODO) {
        val cb = document.createElement("input") as HTMLInputElement
        cb.type = "checkbox"
        cb.className = "todo-checkbox"
        cb.checked = block.checked
        cb.setAttribute("aria-label", "Toggle todo status")
        cb.addEventListener("change", {
            mutate { block.checked = cb.checked }
            el.classList.toggle("done", cb.checked)
        })
        el.appendChild(cb)
    }

    if (block.type == BlockType.BULLET || block.type == BlockType.NUMBERED) {
        val marker = el(
            "span", "bullet-marker",
            if (block.type == BlockType.BULLET) "•" else numberedIndex(workspace.activePage().blocks, idx).toString() + ".",
        )
        marker.setAttribute("aria-hidden", "true")
        el.appendChild(marker)
    }

    val content = document.createElement("div") as HTMLElement
    content.className = "block-content"
    content.contentEditable = "true"
    content.spellcheck = false
    content.asDynamic().dataset["placeholder"] = block.type.placeholder
    content.setAttribute("aria-label", "Block content")
    content.textContent = block.text
    el.appendChild(content)

    content.addEventListener("input", {
        mutate { block.text = content.textContent ?: "" }
        if (content.textContent == "/") openSlashMenu(block, el)
    })
    content.addEventListener("keydown", { e -> blockKeydown(e as KeyboardEvent, block, idx, content) })
    return el
}

fun ForgeBrowser.blockKeydown(e: KeyboardEvent, block: ForgeBlock, idx: Int, content: HTMLElement) {
    val page = workspace.activePage()
    val slashMenuEl = el("slash-menu")
    when {
        e.key == "Enter" && !e.shiftKey -> {
            e.preventDefault()
            closeSlashMenu()
            // headings/quotes exit to paragraph on Enter
            val nextType = block.type.enterContinuation()
            val caret = getCaretOffset(content)
            val full = content.textContent ?: ""
            val tail = full.substring(caret.coerceIn(0, full.length))
            content.textContent = full.substring(0, caret.coerceIn(0, full.length))
            block.text = content.textContent ?: ""
            val next = ForgeBlock(forgeUid(), nextType, tail, checked = false)
            insertBlock(idx + 1, next)
            focusBlock(next.id, atStart = true)
        }
        e.key == "Backspace" && (content.textContent ?: "") == "" -> {
            e.preventDefault()
            closeSlashMenu()
            val focusTarget = if (idx > 0) page.blocks[idx - 1].id else null
            mutate {
                page.blocks.removeAt(idx)
                if (page.blocks.isEmpty()) page.blocks.add(ForgeBlock(forgeUid(), BlockType.P, ""))
            }
            renderBlocks()
            if (focusTarget != null) focusBlock(focusTarget, atStart = false, atEnd = true)
            else el("doc-title")?.focus()
        }
        e.key == "Escape" -> closeSlashMenu()
        slashMenuEl != null && !slashMenuEl.hidden && (e.key == "ArrowDown" || e.key == "ArrowUp") -> {
            e.preventDefault()
            slashNav(if (e.key == "ArrowDown") 1 else -1)
        }
        slashMenuEl != null && !slashMenuEl.hidden && e.key == "Tab" -> {
            e.preventDefault()
            slashPick(activeSlashIndex)
        }
    }
}

fun ForgeBrowser.getCaretOffset(el: HTMLElement): Int {
    val sel = js("window.getSelection()") ?: return 0
    if ((sel.rangeCount as Int) == 0) return 0
    val range = sel.getRangeAt(0).cloneRange()
    range.selectNodeContents(el)
    range.setEnd(sel.getRangeAt(0).endContainer, sel.getRangeAt(0).endOffset)
    return range.toString().length as Int
}

fun ForgeBrowser.focusBlock(blockId: String, atStart: Boolean, atEnd: Boolean = false) {
    val blocksEl = el("doc-blocks") ?: return
    val el = blocksEl.querySelector("[data-block-id=\"$blockId\"] .block-content") as? HTMLElement ?: return
    el.focus()
    val range = document.createRange()
    range.selectNodeContents(el)
    range.collapse(atStart)
    if (atEnd) range.collapse(false)
    val sel = js("window.getSelection()") ?: return
    sel.removeAllRanges()
    sel.addRange(range)
}

fun ForgeBrowser.insertBlock(idx: Int, block: ForgeBlock) {
    mutate { workspace.activePage().blocks.add(idx.coerceIn(0, workspace.activePage().blocks.size), block) }
    renderBlocks()
}

// ── Slash menu ──────────────────────────────────────────────────────────

fun ForgeBrowser.openSlashMenu(block: ForgeBlock, anchorEl: HTMLElement) {
    slashBlockId = block.id
    slashFilter = ""
    activeSlashIndex = 0
    val slashMenuEl = el("slash-menu") ?: return
    val rect = anchorEl.getBoundingClientRect()
    slashMenuEl.style.left = maxOf(8.0, rect.left).toString() + "px"
    slashMenuEl.style.top = (rect.bottom + 6).toString() + "px"
    renderSlashMenu()
    slashMenuEl.hidden = false
}

fun ForgeBrowser.closeSlashMenu() {
    val slashMenuEl = el("slash-menu")
    slashMenuEl?.hidden = true
    val blockId = slashBlockId
    if (blockId != null) {
        val content = el("doc-blocks")?.querySelector("[data-block-id=\"$blockId\"] .block-content") as? HTMLElement
        if (content != null && content.textContent == "/") {
            content.textContent = ""
            mutate { s -> s.pages.flatMap { it.blocks }.firstOrNull { it.id == blockId }?.text = "" }
        }
    }
    slashBlockId = null
}

fun ForgeBrowser.closeSlashMenuSilent() {
    el("slash-menu")?.hidden = true
    slashBlockId = null
}

fun ForgeBrowser.renderSlashMenu() {
    val slashMenuEl = el("slash-menu") ?: return
    slashMenuEl.innerHTML = "<div class=\"slash-menu-label\">Basic blocks</div>"
    val items = slashMatches(slashFilter)
    items.forEachIndexed { i, d ->
        val item = document.createElement("button") as HTMLElement
        val isActive = i == activeSlashIndex
        item.className = "slash-item" + (if (isActive) " active" else "")
        item.setAttribute("aria-label", d.label + " command" + (if (isActive) " (currently selected)" else "") + ": " + d.desc)
        val icon = el("span", "slash-item-icon", d.icon)
        icon.setAttribute("aria-hidden", "true")
        val text = el("span", "slash-item-text")
        text.append(el("span", "slash-item-name", d.label), el("span", "slash-item-desc", d.desc))
        item.append(icon, text)
        item.addEventListener("click", { slashApply(d) })
        slashMenuEl.appendChild(item)
    }
}

fun ForgeBrowser.slashNav(delta: Int) {
    val slashMenuEl = el("slash-menu") ?: return
    val count = slashMenuEl.querySelectorAll(".slash-item").length
    if (count == 0) return
    activeSlashIndex = (activeSlashIndex + delta + count) % count
    renderSlashMenu()
}

fun ForgeBrowser.slashPick(i: Int) {
    val items = slashMatches(slashFilter)
    items.getOrNull(i)?.let { slashApply(it) }
}

fun ForgeBrowser.slashApply(type: BlockType) {
    val blockId = slashBlockId
    if (blockId == null) {
        closeSlashMenu()
        return
    }
    mutate { s ->
        s.pages.flatMap { it.blocks }.firstOrNull { it.id == blockId }?.let { block ->
            block.type = type
            block.text = ""
            if (type == BlockType.TODO) block.checked = false
        }
    }
    closeSlashMenuSilent()
    renderBlocks()
    focusBlock(blockId, atStart = true)
}

// ── Import / export ─────────────────────────────────────────────────────

/** `importDocument`: parse text into a page, adopt it, switch to the doc view. */
fun ForgeBrowser.importDocument(name: String, text: String, format: String?) {
    val page = importedPageOf(name, text, format)
    mutate { s ->
        s.pages.add(page)
        s.activePageId = page.id
        s.view = ForgeView.Doc
    }
    renderAll()
}

fun ForgeBrowser.downloadText(name: String, text: String, type: String = "text/plain;charset=utf-8") {
    val link = document.createElement("a") as HTMLElement
    js(
        """
        var blob = new Blob([text], { type: type });
        link.href = URL.createObjectURL(blob);
        link.download = name;
        """
    )
    link.click()
    scope.launch {
        kotlinx.coroutines.delay(1000)
        js("URL.revokeObjectURL(link.href)")
    }
}
