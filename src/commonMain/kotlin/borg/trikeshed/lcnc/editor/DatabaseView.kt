package borg.trikeshed.lcnc.editor

import borg.trikeshed.lcnc.isam.LcncDatabase

class DatabaseView(val database: LcncDatabase) {
    fun renderHtml(): String {
        val rowsHtml = StringBuilder()
        for (i in 0 until database.pages.a) {
            val page = database.pages.b(i)
            rowsHtml.append("<tr><td>${page.title}</td></tr>\n")
        }
        
        return """
            <div class="lcnc-database" data-database-id="${database.id}">
                <h2>${database.title}</h2>
                <table class="lcnc-database-table">
                    <thead>
                        <tr><th>Title</th></tr>
                    </thead>
                    <tbody>
                        $rowsHtml
                    </tbody>
                </table>
            </div>
        """.trimIndent()
    }
}
