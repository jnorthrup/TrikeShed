package borg.trikeshed.lcnc.editor

import borg.trikeshed.lcnc.isam.LcncBlock

class BlockView(val block: LcncBlock) {
    fun renderHtml(): String {
        val contentStr = block.content?.toString() ?: ""
        return """
            <div class="lcnc-block" data-block-id="${block.id}" data-block-type="${block.type}">
                <div class="block-content">$contentStr</div>
            </div>
        """.trimIndent()
    }
}
