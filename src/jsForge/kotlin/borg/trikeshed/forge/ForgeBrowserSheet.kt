@file:Suppress("UNCHECKED_CAST")

package borg.trikeshed.forge

import borg.trikeshed.forge.sheet.CellRef
import borg.trikeshed.forge.sheet.SheetSort
import borg.trikeshed.forge.sheet.SourceSheet
import borg.trikeshed.forge.sheet.WorkbookSheet
import borg.trikeshed.forge.sheet.cellText
import borg.trikeshed.forge.sheet.isSheetRef
import borg.trikeshed.forge.sheet.selectedWorkbookCell
import borg.trikeshed.forge.sheet.sheetRefId
import borg.trikeshed.forge.sheet.sortedRowIndices
import borg.trikeshed.forge.sheet.workbookColumnName
import borg.trikeshed.forge.sheet.workbookCsvText
import borg.trikeshed.forge.sheet.workbookDisplay
import kotlinx.browser.document
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.HTMLTableCellElement
import org.w3c.dom.HTMLTableElement
import org.w3c.dom.HTMLTableRowElement
import org.w3c.dom.events.KeyboardEvent

/**
 * Sheets adapter — the jsForge DOM half of script.js "Render: sheets (TreeSheets idiom)".
 * Editable workbook grids and read-only nested source sheets; every decision (formula engine,
 * sort, column naming, CSV) is commonMain (`sheet/ForgeWorkbook.kt`).
 */

fun workbookButton(label: String, title: String, action: () -> Unit): HTMLElement {
    val button = document.createElement("button") as HTMLElement
    button.className = "topbar-btn"
    button.setAttribute("type", "button")
    button.textContent = label
    button.title = title
    button.addEventListener("click", { action() })
    return button
}

fun ForgeBrowser.currentSheetId(): String? {
    val id = workspace.sheetId
    if (id != null && (sourceSheetById.containsKey(id) || workspace.workbook.sheetById(id) != null)) return id
    return workspace.workbook.activeSheetId.ifEmpty { null }
        ?: sourceSheets.firstOrNull { it.parent == null }?.id
}

// ── Workbook grid ───────────────────────────────────────────────────────

fun ForgeBrowser.focusWorkbookCell(row: Int, col: Int) {
    val wrap = el("sheet-grid-wrap") ?: return
    val selectedCells = wrap.querySelectorAll("td.workbook-cell.selected")
    for (i in 0 until selectedCells.length) (selectedCells.item(i) as? HTMLElement)?.classList?.remove("selected")
    val inputs = wrap.querySelectorAll("input[data-workbook-cell=\"true\"]")
    for (i in 0 until inputs.length) {
        val input = inputs.item(i) as? HTMLInputElement ?: continue
        if (input.asDynamic().dataset["row"].toString().toInt() == row &&
            input.asDynamic().dataset["col"].toString().toInt() == col
        ) {
            (input.closest("td") as? HTMLElement)?.classList?.add("selected")
            input.focus()
            input.select()
            return
        }
    }
}

fun ForgeBrowser.updateWorkbookFormulaBar(sheet: WorkbookSheet, row: Int, col: Int, value: String?) {
    val formula = document.getElementById("sheet-formula") as? HTMLInputElement
    if (formula != null && document.activeElement !== formula) {
        formula.value = value ?: sheet.rawCell(row, col)
    }
    val status = document.getElementById("sheet-status")
    status?.textContent = workbookColumnName(col) + (row + 1) + "  ·  " + workbookDisplay(sheet, row, col)
}

fun ForgeBrowser.refreshWorkbookValues(sheet: WorkbookSheet) {
    val wrap = el("sheet-grid-wrap") ?: return
    val inputs = wrap.querySelectorAll("input[data-workbook-cell=\"true\"]")
    for (i in 0 until inputs.length) {
        val input = inputs.item(i) as? HTMLInputElement ?: continue
        val row = input.asDynamic().dataset["row"].toString().toInt()
        val col = input.asDynamic().dataset["col"].toString().toInt()
        val raw = sheet.rawCell(row, col)
        input.asDynamic().dataset["raw"] = raw
        if (document.activeElement !== input) {
            input.value = if (raw.startsWith('=')) workbookDisplay(sheet, row, col) else raw
        }
        val td = input.closest("td") as? HTMLElement ?: continue
        td.classList.toggle("formula", raw.startsWith('='))
        td.classList.toggle("error", raw.startsWith('=') && workbookDisplay(sheet, row, col) == "#ERR")
        td.title = if (raw.startsWith('=')) raw + "  →  " + workbookDisplay(sheet, row, col) else ""
    }
}

/** Commit a cell edit; returns whether the value changed. */
fun ForgeBrowser.commitWorkbookCell(sheet: WorkbookSheet, row: Int, col: Int, value: String, next: CellRef? = null): Boolean {
    val text = value
    val old = sheet.rawCell(row, col)
    mutate { s ->
        val target = s.workbook.sheetById(sheet.id) ?: return@mutate
        while (target.rows.size <= row) target.rows.add(MutableList(target.columns.size) { "" })
        target.rows[row][col] = text
        s.workbook.selected = CellRef(row, col)
        s.workbook.activeSheetId = sheet.id
        s.sheetId = sheet.id
    }
    refreshWorkbookValues(sheet)
    updateWorkbookFormulaBar(sheet, row, col, text)
    if (next != null) scope.launch { delay(0); focusWorkbookCell(next.row, next.col) }
    return old != text
}

fun ForgeBrowser.addWorkbookSheet() {
    val id = forgeUid()
    val index = workspace.workbook.sheets.size + 1
    mutate { s ->
        s.workbook.sheets.add(
            WorkbookSheet(
                id = id, title = "Sheet $index",
                columns = mutableListOf("A", "B", "C"),
                rows = mutableListOf(mutableListOf("", "", "")),
            )
        )
        s.workbook.activeSheetId = id
        s.workbook.selected = CellRef(0, 0)
        s.sheetId = id
    }
    renderSheet()
    scope.launch { delay(0); focusWorkbookCell(0, 0) }
}

fun ForgeBrowser.addWorkbookRow(sheet: WorkbookSheet) {
    mutate { s ->
        val target = s.workbook.sheetById(sheet.id) ?: return@mutate
        target.rows.add(MutableList(target.columns.size) { "" })
        s.workbook.selected = CellRef(target.rows.size - 1, 0)
    }
    renderSheet()
    scope.launch { delay(0); focusWorkbookCell(sheet.rows.size - 1, 0) }
}

fun ForgeBrowser.addWorkbookColumn(sheet: WorkbookSheet) {
    mutate { s ->
        val target = s.workbook.sheetById(sheet.id) ?: return@mutate
        target.columns.add(workbookColumnName(target.columns.size))
        for (row in target.rows) row.add("")
        s.workbook.selected = CellRef(0, target.columns.size - 1)
    }
    renderSheet()
    scope.launch { delay(0); focusWorkbookCell(0, sheet.columns.size - 1) }
}

fun ForgeBrowser.exportWorkbookCsv(sheet: WorkbookSheet) {
    downloadText(
        sheet.title.ifEmpty { "workbook" }.replace(Regex("[^A-Za-z0-9_-]+"), "-") + ".csv",
        workbookCsvText(sheet),
        "text/csv;charset=utf-8",
    )
}

fun ForgeBrowser.renderWorkbookToolbar(sheet: WorkbookSheet) {
    val toolbar = el("sheet-toolbar") ?: return
    toolbar.clearChildren()
    toolbar.appendChild(el("span", "sheet-name", sheet.title))
    toolbar.appendChild(workbookButton("New sheet", "Add an editable sheet") { addWorkbookSheet() })
    toolbar.appendChild(workbookButton("Add row", "Append a row") { addWorkbookRow(sheet) })
    toolbar.appendChild(workbookButton("Add column", "Append a column") { addWorkbookColumn(sheet) })
    toolbar.appendChild(workbookButton("Export CSV", "Download this sheet as CSV") { exportWorkbookCsv(sheet) })
    val formula = document.createElement("input") as HTMLInputElement
    formula.id = "sheet-formula"
    formula.className = "sheet-formula"
    formula.type = "text"
    formula.placeholder = "Formula or value"
    formula.setAttribute("aria-label", "Selected cell formula")
    val selected = selectedWorkbookCell(sheet, workspace.workbook.selected)
    formula.value = sheet.rawCell(selected.row, selected.col)
    val commitFormula = {
        val current = selectedWorkbookCell(sheet, workspace.workbook.selected)
        commitWorkbookCell(sheet, current.row, current.col, formula.value)
        formula.value = sheet.rawCell(current.row, current.col)
    }
    formula.addEventListener("keydown", { event ->
        val ke = event as KeyboardEvent
        val current = selectedWorkbookCell(sheet, workspace.workbook.selected)
        when (ke.key) {
            "Enter" -> {
                ke.preventDefault()
                commitFormula()
                focusWorkbookCell(current.row, current.col)
            }
            "Escape" -> {
                formula.value = sheet.rawCell(current.row, current.col)
                formula.blur()
            }
        }
    })
    formula.addEventListener("blur", { commitFormula() })
    toolbar.appendChild(formula)
    val status = el("span", "sheet-status")
    status.id = "sheet-status"
    toolbar.appendChild(status)
    toolbar.appendChild(el("span", "sheet-help", "Use =SUM(A1:A3), +, −, ×, ÷, IF, or ROUND"))
    updateWorkbookFormulaBar(sheet, selected.row, selected.col, null)
}

fun ForgeBrowser.renderReadOnlyToolbar(sheet: SourceSheet?) {
    val toolbar = el("sheet-toolbar") ?: return
    toolbar.clearChildren()
    toolbar.appendChild(el("span", "sheet-name", sheet?.title ?: "Source sheets"))
    toolbar.appendChild(workbookButton("New sheet", "Add an editable sheet") { addWorkbookSheet() })
    toolbar.appendChild(el("span", "sheet-status", "Read-only source projection"))
}

fun ForgeBrowser.buildWorkbookTable(sheet: WorkbookSheet): HTMLElement {
    val table = document.createElement("table") as HTMLElement
    table.className = "sheet workbook-sheet"
    table.asDynamic().dataset["sheet"] = sheet.id
    val head = document.createElement("thead")
    val header = document.createElement("tr")
    val corner = document.createElement("th") as HTMLElement
    corner.textContent = "#"
    corner.setAttribute("aria-label", "Row number")
    header.appendChild(corner)
    sheet.columns.forEachIndexed { col, column ->
        val th = document.createElement("th") as HTMLElement
        th.textContent = workbookColumnName(col) + "  " + column.ifEmpty { "Column ${col + 1}" }
        header.appendChild(th)
    }
    head.appendChild(header)
    table.appendChild(head)
    val body = document.createElement("tbody")
    val rowCount = maxOf(1, sheet.rows.size)
    val selected = selectedWorkbookCell(sheet, workspace.workbook.selected)
    for (row in 0 until rowCount) {
        val tr = document.createElement("tr")
        val number = document.createElement("th") as HTMLElement
        number.setAttribute("scope", "row")
        number.textContent = (row + 1).toString()
        tr.appendChild(number)
        for (col in sheet.columns.indices) {
            val td = document.createElement("td") as HTMLElement
            val input = document.createElement("input") as HTMLInputElement
            val raw = sheet.rawCell(row, col)
            val isFormula = raw.startsWith('=')
            td.className = "workbook-cell" + (if (isFormula) " formula" else "")
            if (isFormula && workbookDisplay(sheet, row, col) == "#ERR") td.classList.add("error")
            if (row == selected.row && col == selected.col) td.classList.add("selected")
            input.type = "text"
            input.asDynamic().dataset["workbookCell"] = "true"
            input.asDynamic().dataset["row"] = row.toString()
            input.asDynamic().dataset["col"] = col.toString()
            input.asDynamic().dataset["raw"] = raw
            input.value = if (isFormula) workbookDisplay(sheet, row, col) else raw
            input.setAttribute("aria-label", workbookColumnName(col) + (row + 1))
            td.title = if (isFormula) raw + "  →  " + workbookDisplay(sheet, row, col) else ""
            input.addEventListener("focus", {
                workspace.workbook.selected = CellRef(row, col)
                input.value = sheet.rawCell(row, col)
                input.asDynamic().dataset["raw"] = input.value
                val wrap = el("sheet-grid-wrap")
                val selectedCells = wrap?.querySelectorAll("td.workbook-cell.selected")
                if (selectedCells != null) {
                    for (i in 0 until selectedCells.length) (selectedCells.item(i) as? HTMLElement)?.classList?.remove("selected")
                }
                td.classList.add("selected")
                updateWorkbookFormulaBar(sheet, row, col, input.value)
            })
            input.addEventListener("input", { updateWorkbookFormulaBar(sheet, row, col, input.value) })
            input.addEventListener("blur", { commitWorkbookCell(sheet, row, col, input.value) })
            input.addEventListener("keydown", { event ->
                val ke = event as KeyboardEvent
                when (ke.key) {
                    "Escape" -> {
                        ke.preventDefault()
                        input.value = input.asDynamic().dataset["raw"].toString()
                        input.blur()
                    }
                    "Enter", "Tab" -> {
                        ke.preventDefault()
                        val next = if (ke.key == "Enter")
                            CellRef(row = minOf(row + 1, maxOf(0, sheet.rows.size - 1)), col = col)
                        else
                            CellRef(row = row, col = minOf(col + 1, maxOf(0, sheet.columns.size - 1)))
                        commitWorkbookCell(sheet, row, col, input.value, next)
                    }
                }
            })
            td.appendChild(input)
            tr.appendChild(td)
        }
        body.appendChild(tr)
    }
    table.appendChild(body)
    return table
}

// ── Source sheets (read-only, nested) ───────────────────────────────────

fun ForgeBrowser.buildSheetTable(sheet: SourceSheet, depth: Int): HTMLElement {
    val table = document.createElement("table") as HTMLElement
    table.className = "sheet"
    table.asDynamic().dataset["sheet"] = sheet.id
    val thead = document.createElement("thead")
    val hr = document.createElement("tr")
    sheet.columns.forEachIndexed { ci, col ->
        val th = document.createElement("th") as HTMLElement
        th.textContent = col.name
        th.appendChild(el("span", "sheet-type", col.type))
        val sort = sheetSort[sheet.id]
        if (sort != null && sort.col == ci) th.classList.add(if (sort.dir.id == "desc") "sorted-desc" else "sorted-asc")
        th.addEventListener("click", {
            val cur = sheetSort[sheet.id]
            sheetSort[sheet.id] = if (cur == null) SheetSort(ci, borg.trikeshed.forge.sheet.SortDir.Asc) else cur.toggled(ci)
            renderSheet()
        })
        hr.appendChild(th)
    }
    thead.appendChild(hr)
    table.appendChild(thead)
    val tbody = document.createElement("tbody")
    for (i in sortedRowIndices(sheet.rows, sheetSort[sheet.id])) {
        val r = sheet.rows[i]
        val tr = document.createElement("tr")
        r.forEachIndexed { ci, cell ->
            val td = document.createElement("td") as HTMLElement
            td.tabIndex = 0
            td.asDynamic().dataset["row"] = i.toString()
            td.asDynamic().dataset["col"] = ci.toString()
            if (isSheetRef(cell)) {
                td.className = "sheet-ref-cell"
                val key = sheet.id + "|" + i + "|" + ci
                val refId = sheetRefId(cell)!!
                val child = sourceSheetById[refId]
                val ref = el("button", "sheet-ref")
                val isExpanded = workspace.sheetExpanded[key] == true
                ref.setAttribute("aria-expanded", isExpanded.toString())
                val sheetName = child?.title?.split('/')?.last() ?: refId
                ref.setAttribute("aria-label", (if (isExpanded) "Collapse" else "Expand") + " sheet reference: " + sheetName)
                val caret = el("span", "", if (isExpanded) "▾" else "▸")
                caret.setAttribute("aria-hidden", "true")
                ref.append(caret, el("span", "", "▦ $sheetName"),
                    el("span", "sheet-count", child?.let { it.rows.size.toString() + " rows" } ?: ""))
                ref.title = "Click: expand in place · Open: zoom into $refId"
                ref.addEventListener("click", { ev ->
                    ev.stopPropagation()
                    workspace.sheetExpanded[key] = !isExpanded
                    mutate { it.sheetExpanded[key] = workspace.sheetExpanded[key] == true }
                    renderSheet()
                })
                td.appendChild(ref)
                val open = el("button", "sheet-ref sheet-ref-open", "open ↗")
                open.title = "Zoom into this sheet"
                open.setAttribute("aria-label", "Zoom into sheet $refId")
                open.addEventListener("click", { ev -> ev.stopPropagation(); openSheet(refId) })
                td.appendChild(open)
                if (isExpanded && child != null && depth < 6) td.appendChild(buildSheetTable(child, depth + 1))
            } else {
                td.textContent = cellText(cell)
            }
            tr.appendChild(td)
        }
        tbody.appendChild(tr)
    }
    table.appendChild(tbody)
    return table
}

fun ForgeBrowser.openSheet(id: String) {
    if (!sourceSheetById.containsKey(id) && workspace.workbook.sheetById(id) == null) return
    mutate { s ->
        s.sheetId = id
        if (s.workbook.sheetById(id) != null) s.workbook.activeSheetId = id
    }
    renderSheet()
}

fun ForgeBrowser.sheetTab(title: String, count: String, active: Boolean, onOpen: () -> Unit): HTMLElement {
    val b = document.createElement("button") as HTMLElement
    b.className = "sheet-tab" + (if (active) " active" else "")
    b.setAttribute("role", "tab")
    b.setAttribute("aria-selected", active.toString())
    b.textContent = title
    b.appendChild(el("span", "sheet-count", count))
    b.addEventListener("click", { onOpen() })
    return b
}

fun ForgeBrowser.renderSheet() {
    val tabsEl = el("sheet-tabs") ?: return
    val crumbsEl = el("sheet-crumbs") ?: return
    val wrapEl = el("sheet-grid-wrap") ?: return
    val emptyEl = el("sheet-empty") ?: return
    tabsEl.clearChildren(); crumbsEl.clearChildren(); wrapEl.clearChildren()
    val workbookSheets = workspace.workbook.sheets
    if (workbookSheets.isEmpty() && sourceSheets.isEmpty()) {
        el("sheet-toolbar")?.clearChildren()
        emptyEl.hidden = false
        return
    }
    emptyEl.hidden = true
    val curId = currentSheetId()
    val workbook = workspace.workbook.sheetById(curId)
    if (workbook != null) {
        renderWorkbookToolbar(workbook)
        tabsEl.appendChild(sheetTab(workbook.title, "${workbook.rows.size} × ${workbook.columns.size}", true) {
            openSheet(workbook.id)
        })
        for (other in workbookSheets) {
            if (other.id == workbook.id) continue
            tabsEl.appendChild(sheetTab(other.title, "${other.rows.size} × ${other.columns.size}", false) {
                openSheet(other.id)
            })
        }
        val crumb = document.createElement("button") as HTMLElement
        crumb.textContent = workbook.title
        crumb.setAttribute("aria-label", "Current sheet: " + workbook.title)
        crumbsEl.appendChild(crumb)
        wrapEl.appendChild(buildWorkbookTable(workbook))
        return
    }
    val cur = curId?.let { sourceSheetById[it] }
    if (cur == null) {
        renderReadOnlyToolbar(null)
        emptyEl.hidden = false
        return
    }
    renderReadOnlyToolbar(cur)
    for (workbookSheet in workbookSheets) {
        tabsEl.appendChild(sheetTab(workbookSheet.title, "${workbookSheet.rows.size} × ${workbookSheet.columns.size}", false) {
            openSheet(workbookSheet.id)
        })
    }
    // tabs = root sheets (one per source: blackboard cursor, confix doc, …)
    var rootOf: SourceSheet? = cur
    while (rootOf?.parent != null && sourceSheetById.containsKey(rootOf?.parent)) {
        rootOf = sourceSheetById[rootOf?.parent]
    }
    for (sh in sourceSheets.filter { it.parent == null }) {
        tabsEl.appendChild(sheetTab(sh.title, "${sh.rows.size} × ${sh.columns.size}", rootOf?.id == sh.id) {
            openSheet(sh.id)
        })
    }
    // breadcrumb = parent chain (zoom path)
    val chain = mutableListOf<SourceSheet>()
    var p: SourceSheet? = cur
    while (p != null) {
        chain.add(0, p)
        p = p.parent?.let { sourceSheetById[it] }
    }
    chain.forEachIndexed { i, sh ->
        if (i > 0) crumbsEl.appendChild(document.createTextNode(" / "))
        val title = sh.id.split('/').last().ifEmpty { sh.title }
        val b = document.createElement("button") as HTMLElement
        b.textContent = title
        b.setAttribute("aria-label", "Navigate to parent sheet: $title")
        b.addEventListener("click", { openSheet(sh.id) })
        crumbsEl.appendChild(b)
    }
    wrapEl.appendChild(buildSheetTable(cur, 0))
}

/** Arrow-key cell navigation within the focused table. */
fun ForgeBrowser.wireSheetKeys() {
    el("sheet-grid-wrap")?.addEventListener("keydown", { e ->
        val ke = e as KeyboardEvent
        val target = ke.target as? HTMLElement ?: return@addEventListener
        if (target.matches("input[data-workbook-cell=\"true\"]")) return@addEventListener
        val td = target.closest("td") as? HTMLTableCellElement ?: return@addEventListener
        val tr = td.parentElement as? HTMLTableRowElement ?: return@addEventListener
        val table = tr.closest("table") as? HTMLTableElement ?: return@addEventListener
        val r = tr.rowIndex - 1
        val c = td.cellIndex
        val tbody = table.tBodies.item(0) as? org.w3c.dom.HTMLTableSectionElement ?: return@addEventListener
        val rows = tbody.rows
        var focusTarget: org.w3c.dom.Element? = null
        when (ke.key) {
            "ArrowDown" -> focusTarget = rows.item(r + 1)?.let { (it as HTMLTableRowElement).cells.item(c) }
            "ArrowUp" -> if (r - 1 >= 0) focusTarget = rows.item(r - 1)?.let { (it as HTMLTableRowElement).cells.item(c) }
            "ArrowRight" -> focusTarget = tr.cells.item(c + 1)
            "ArrowLeft" -> focusTarget = tr.cells.item(c - 1)
            "Enter" -> {
                val btn = td.querySelector(".sheet-ref") as? HTMLButtonElement
                if (btn != null) {
                    btn.click()
                    ke.preventDefault()
                    return@addEventListener
                }
            }
        }
        if (focusTarget != null) {
            ke.preventDefault()
            (focusTarget as? HTMLElement)?.focus()
        }
    })
}
