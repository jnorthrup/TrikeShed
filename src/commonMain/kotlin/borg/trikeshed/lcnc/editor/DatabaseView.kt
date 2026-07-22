/*
 * Copyright (c) TrikeShed Contributors
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 */
package borg.trikeshed.lcnc.editor

import borg.trikeshed.context.nuid.Capability
import borg.trikeshed.context.nuid.Nonce
import borg.trikeshed.context.nuid.Subnet
import borg.trikeshed.context.nuid.nuid
import borg.trikeshed.lcnc.ccek.IngestStateElement
import borg.trikeshed.lcnc.isam.LcncDatabase
import borg.trikeshed.lcnc.isam.LcncPage
import borg.trikeshed.lcnc.reactor.ReactorAction
import borg.trikeshed.lcnc.collections.associative.DatabaseSchema
import borg.trikeshed.lcnc.collections.associative.PageProperties
import borg.trikeshed.lcnc.collections.associative.PropertyType
import borg.trikeshed.lcnc.collections.associative.PropertyValue
import borg.trikeshed.lcnc.isam.LcncBlock
import borg.trikeshed.lib.j
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class DatabaseView(var database: LcncDatabase, val ingestState: IngestStateElement, val schema: DatabaseSchema) {
    private val myNuid = nuid(Capability.BlackBoard, Nonce.RandomBytes(), Subnet.core)

    fun open() {
        CoroutineScope(Dispatchers.Default).launch {
            ingestState.publishEntity(ReactorAction.Opened(myNuid))
        }
    }

    fun activate() {
        CoroutineScope(Dispatchers.Default).launch {
            ingestState.publishEntity(ReactorAction.Activated(myNuid))
        }
    }

    fun drain() {
        CoroutineScope(Dispatchers.Default).launch {
            ingestState.publishEntity(ReactorAction.Draining(myNuid))
        }
    }

    fun close() {
        CoroutineScope(Dispatchers.Default).launch {
            ingestState.publishEntity(ReactorAction.Closed(myNuid))
        }
    }

    fun addRow(page: LcncPage) {
        // We simulate row addition by publishing the new page entity
        CoroutineScope(Dispatchers.Default).launch {
            ingestState.publishEntity(ReactorAction.PublishEntity(myNuid, page))
        }
    }

    fun renderHtml(): String = html {
        div(classes = "lcnc-database", id = "db-${database.id}") {
            text("<div data-database-id=\"${database.id}\" style=\"display:none;\"></div>")
            
            // Header with Sort and Filter placeholders
            div(classes = "lcnc-database-header") {
                text("<h2>${database.title} <span class=\"lcnc-row-count\">(${database.pages.a} rows)</span></h2>")
                div(classes = "lcnc-database-controls") {
                    for (prop in schema.properties.values) {
                        text("<div class=\"lcnc-database-filter\"><input type=\"text\" placeholder=\"Filter ${prop.name}...\" data-column-id=\"${prop.id}\" onkeyup=\"window.lcncFilterColumn('${database.id}', '${prop.id}', this.value)\"/></div>")
                    }
                    text("<button onclick=\"window.lcncAddColumn('${database.id}')\">+ Add Column</button>")
                }
            }

            // Table
            text("<table class=\"lcnc-database-table\">")
            text("<thead><tr>")
            text("<th><button class=\"lcnc-database-sort\" data-column=\"title\" onclick=\"window.lcncSortColumn('${database.id}', 'title')\">Title</button></th>")
            for (prop in schema.properties.values) {
                text("<th>")
                text("<button class=\"lcnc-database-sort\" data-column=\"${prop.id}\" onclick=\"window.lcncSortColumn('${database.id}', '${prop.id}')\">${prop.name}</button>")
                text("<button class=\"lcnc-col-delete\" onclick=\"window.lcncDeleteColumn('${database.id}', '${prop.id}')\">x</button>")
                text("<button class=\"lcnc-col-rename\" onclick=\"window.lcncRenameColumn('${database.id}', '${prop.id}')\">✎</button>")
                text("</th>")
            }
            text("<th>Actions</th>")
            text("</tr></thead>")
            text("<tbody>")
            
            for (i in 0 until database.pages.a) {
                val page = database.pages.b(i)
                val pageProps = page.contentBlocks.takeIf { it.a > 0 }?.let { it.b(0).content as? PageProperties }
                text("<tr>")
                text("<td>${page.title}</td>")
                
                for (prop in schema.properties.values) {
                    text("<td>")
                    val value = pageProps?.properties?.get(prop.id)?.value
                    
                    val onChangeHandler: (PropertyChangeEvent) -> Unit = { event ->
                        val oldPropsMap = pageProps?.properties ?: emptyMap()
                        val newPropsMap = oldPropsMap + (prop.id to PropertyValue(prop.id, prop.type, event.newValue))
                        val newPageProps = PageProperties(newPropsMap)
                        
                        val newBlock = if (page.contentBlocks.a > 0) {
                            page.contentBlocks.b(0).copy(content = newPageProps)
                        } else {
                            LcncBlock(id = "props-${page.id}", type = "properties", parentId = page.id, content = newPageProps)
                        }

                        val newPage = page.copy(contentBlocks = 1 j { newBlock })
                        
                        val newPages = database.pages.a j { index: Int ->
                            val p = database.pages.b(index)
                            if (p.id == page.id) newPage else p
                        }
                        
                        database = database.copy(pages = newPages)
                        
                        CoroutineScope(Dispatchers.Default).launch {
                            ingestState.publishEntity(ReactorAction.PublishEntity(myNuid, database))
                        }
                    }

                    val editor = when (prop.type) {
                        PropertyType.TEXT -> TextPropertyEditor(prop, value, onChangeHandler)
                        PropertyType.SELECT -> SelectPropertyEditor(prop, value, onChangeHandler)
                        PropertyType.MULTI_SELECT -> MultiSelectPropertyEditor(prop, value, onChangeHandler)
                        PropertyType.CHECKBOX -> CheckboxPropertyEditor(prop, value, onChangeHandler)
                        PropertyType.NUMBER -> NumberPropertyEditor(prop, value, onChangeHandler)
                        PropertyType.DATE -> DatePropertyEditor(prop, value, onChangeHandler)
                        PropertyType.URL -> UrlPropertyEditor(prop, value, onChangeHandler)
                        PropertyType.EMAIL -> EmailPropertyEditor(prop, value, onChangeHandler)
                        PropertyType.PHONE_NUMBER -> PhonePropertyEditor(prop, value, onChangeHandler)
                        else -> TextPropertyEditor(prop, value, onChangeHandler)
                    }
                    text(editor.renderHtml())
                    text("</td>")
                }
                
                text("<td>")
                text("<button onclick=\"window.lcncDuplicateRow('${database.id}', '${page.id}')\">Copy</button>")
                text("<button onclick=\"window.lcncDeleteRow('${database.id}', '${page.id}')\">Delete</button>")
                text("</td>")
                text("</tr>")
            }
            
            text("</tbody>")
            text("</table>")
            
            // Pagination/Add Row
            div(classes = "lcnc-database-footer") {
                text("<button onclick=\"window.lcncAddRow('${database.id}')\">+ New Row</button>")
                text("<button class=\"lcnc-load-more\">Load More</button>")
            }
        }
    }
}
