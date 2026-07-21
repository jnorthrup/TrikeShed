/*
 * Copyright (c) TrikeShed Contributors
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 */
package borg.trikeshed.lcnc.editor

/**
 * LcncJsBridge outlines the Javascript-to-Kotlin integration API required to
 * host the LCNC Visual Editor within a ForgeWindowManager (e.g., BrowserForgeWindowManager).
 *
 * Because this implementation uses an SSR-like Kotlin HTML DSL instead of a 
 * client-side framework, the rendered HTML relies on global `window.lcnc...` 
 * Javascript functions to dispatch DOM events (clicks, keyups, onchange) back 
 * into the Kotlin process runtime.
 * 
 * Target implementations (e.g., jsMain, wasmJsMain) MUST inject a JS script 
 * implementing these globals that invoke this interface exported to JS.
 */
interface LcncJsBridge {
    // ── Block Editor Events ──────────────────────────────────────────────
    fun onMoveBlockUp(blockId: String)
    fun onMoveBlockDown(blockId: String)
    fun onIndentBlock(blockId: String)
    fun onOutdentBlock(blockId: String)
    fun onInsertBlock(afterBlockId: String)
    fun onDeleteBlock(blockId: String)
    fun onChangeBlockType(blockId: String, newType: String)
    fun onUpdateBlockContent(blockId: String, newHtmlContent: String)

    // ── Database View Events ─────────────────────────────────────────────
    fun onFilterColumn(databaseId: String, columnId: String, filterText: String)
    fun onSortColumn(databaseId: String, columnId: String)
    fun onAddColumn(databaseId: String)
    fun onDeleteColumn(databaseId: String, columnId: String)
    fun onRenameColumn(databaseId: String, columnId: String)
    fun onAddRow(databaseId: String)
    fun onDuplicateRow(databaseId: String, rowId: String)
    fun onDeleteRow(databaseId: String, rowId: String)
    
    // ── Property Editor Events ───────────────────────────────────────────
    fun onPropChange(propertyId: String, newValue: Any?)
}
