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
                text("<button onclick=\"window.lcncMoveBlockUp('${block.id}')\" aria-label=\"Move block up\" title=\"Move block up\"><span aria-hidden=\"true\">↑</span></button>")
                text("<button onclick=\"window.lcncMoveBlockDown('${block.id}')\" aria-label=\"Move block down\" title=\"Move block down\"><span aria-hidden=\"true\">↓</span></button>")
                text("<button onclick=\"window.lcncIndentBlock('${block.id}')\" aria-label=\"Indent block\" title=\"Indent block\"><span aria-hidden=\"true\">→</span></button>")
                text("<button onclick=\"window.lcncOutdentBlock('${block.id}')\" aria-label=\"Outdent block\" title=\"Outdent block\"><span aria-hidden=\"true\">←</span></button>")
                
                // Block creation menu
                text("<div class=\"lcnc-block-menu\">")
                text("<button onclick=\"window.lcncInsertBlock('${block.id}')\" aria-label=\"Insert new block\" title=\"Insert new block\"><span aria-hidden=\"true\">+</span></button>")
                text("<select onchange=\"window.lcncChangeBlockType('${block.id}', this.value)\" aria-label=\"Change block type\">")
                text("<option value=\"paragraph\"${if(block.type=="paragraph") " selected" else ""}>Text</option>")
                text("<option value=\"heading_1\"${if(block.type=="heading_1") " selected" else ""}>Heading 1</option>")
                text("<option value=\"heading_2\"${if(block.type=="heading_2") " selected" else ""}>Heading 2</option>")
                text("<option value=\"heading_3\"${if(block.type=="heading_3") " selected" else ""}>Heading 3</option>")
                text("<option value=\"to_do\"${if(block.type=="to_do") " selected" else ""}>TODO</option>")
                text("<option value=\"bulleted_list_item\"${if(block.type=="bulleted_list_item") " selected" else ""}>List</option>")
                text("<option value=\"quote\"${if(block.type=="quote") " selected" else ""}>Quote</option>")
                text("<option value=\"code\"${if(block.type=="code") " selected" else ""}>Code</option>")
                text("</select>")
                text("</div>")

                text("<button onclick=\"window.lcncDeleteBlock('${block.id}')\" aria-label=\"Delete block\" title=\"Delete block\"><span aria-hidden=\"true\">x</span></button>")
            }

            // Block Content editable area
            val contentStr = block.content?.toString() ?: ""
            div(classes = "lcnc-block-content") {
                text("<div contenteditable=\"true\" aria-label=\"Block content\" onblur=\"window.lcncUpdateBlockContent('${block.id}', this.innerText)\">$contentStr</div>")
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
