/*
 * Copyright (c) TrikeShed Contributors
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 */
package borg.trikeshed.lcnc.editor

import borg.trikeshed.context.nuid.Nuid
import borg.trikeshed.lcnc.ccek.IngestStateElement
import borg.trikeshed.lcnc.isam.LcncBlock
import borg.trikeshed.lcnc.reactor.ReactorAction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import borg.trikeshed.lib.Series
import borg.trikeshed.context.nuid.Capability
import borg.trikeshed.context.nuid.Nonce
import borg.trikeshed.context.nuid.Subnet
import borg.trikeshed.context.nuid.nuid

class BlockEditor(var block: LcncBlock, val ingestState: IngestStateElement) {
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

    fun updateContent(newContent: Any?) {
        block = block.copy(content = newContent)
        CoroutineScope(Dispatchers.Default).launch {
            ingestState.publishEntity(ReactorAction.PublishEntity(myNuid, block))
        }
    }

    fun renderHtml(): String = html {
        div(classes = "lcnc-block", id = "block-${block.id}") {
            // Render block attributes for JS to pick up
            text("<div data-block-id=\"${block.id}\" data-block-type=\"${block.type}\" data-parent-id=\"${block.parentId ?: ""}\" style=\"display:none;\"></div>")
            
            // Block Controls (Move up, move down, insert, delete, indent, outdent)
            div(classes = "lcnc-block-controls") {
                text("<button onclick=\"window.lcncMoveBlockUp('${block.id}')\">↑</button>")
                text("<button onclick=\"window.lcncMoveBlockDown('${block.id}')\">↓</button>")
                text("<button onclick=\"window.lcncIndentBlock('${block.id}')\">→</button>")
                text("<button onclick=\"window.lcncOutdentBlock('${block.id}')\">←</button>")
                
                // Block creation menu
                text("<div class=\"lcnc-block-menu\">")
                text("<button onclick=\"window.lcncInsertBlock('${block.id}')\">+</button>")
                text("<select onchange=\"window.lcncChangeBlockType('${block.id}', this.value)\">")
                text("<option value=\"paragraph\"${if(block.type=="paragraph") " selected" else ""}>Text</option>")
                text("<option value=\"heading_1\"${if(block.type=="heading_1") " selected" else ""}>Heading 1</option>")
                text("<option value=\"heading_2\"${if(block.type=="heading_2") " selected" else ""}>Heading 2</option>")
                text("<option value=\"bulleted_list_item\"${if(block.type=="bulleted_list_item") " selected" else ""}>List</option>")
                text("<option value=\"code\"${if(block.type=="code") " selected" else ""}>Code</option>")
                text("<option value=\"divider\"${if(block.type=="divider") " selected" else ""}>Divider</option>")
                text("</select>")
                text("</div>")

                text("<button onclick=\"window.lcncDeleteBlock('${block.id}')\">x</button>")
            }

            // Block Content editable area
            val contentStr = block.content?.toString() ?: ""
            div(classes = "lcnc-block-content") {
                text("<div contenteditable=\"true\" onblur=\"window.lcncUpdateBlockContent('${block.id}', this.innerText)\">$contentStr</div>")
            }

            // Render children recursively
            val children = block.children
            if (children != null && children.a > 0) {
                div(classes = "lcnc-block-children") {
                    for (i in 0 until children.a) {
                        val child = children.b(i)
                        val childEditor = BlockEditor(child, ingestState)
                        text(childEditor.renderHtml())
                    }
                }
            }
        }
    }
}
