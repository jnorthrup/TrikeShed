package borg.trikeshed.rdf

import borg.trikeshed.cursor.*
import borg.trikeshed.lib.*
import borg.trikeshed.parse.jsonOf
import borg.trikeshed.parse.kursive.sql.isRdfTerm

/**
 * SPARQL 1.1 query results over a solution cursor (one column per variable, cells as term lexemes or
 * the aggregate values the planner computed): the W3C JSON format, CSV and TSV. An unbound variable
 * is an absent binding; a non-lexeme cell (a count, a sum) is a typed literal of the matching XSD type.
 */
object SparqlResults {
    const val JSON = "application/sparql-results+json"
    const val CSV = "text/csv"
    const val TSV = "text/tab-separated-values"

    /** `{"head":{"vars":[…]},"results":{"bindings":[{v:{type,value,…}}]}}`. */
    fun json(rows: Cursor, variables: List<String> = names(rows)): String = buildString {
        append("{\"head\":{\"vars\":[").append(variables.joinToString(",") { jsonOf(it) }).append("]},\"results\":{\"bindings\":[")
        for (r in 0 until rows.size) {
            if (r > 0) append(',')
            append('{')
            var first = true
            val values = rows[r].values
            for (c in variables.indices) {
                val term = termOf(values[c]) ?: continue
                if (!first) append(','); first = false
                jsonOf(variables[c], this); append(':')
                append("{\"type\":").append(jsonOf(term.type)).append(",\"value\":").append(jsonOf(term.value))
                term.lang?.let { append(",\"xml:lang\":").append(jsonOf(it)) }
                term.datatype?.let { append(",\"datatype\":").append(jsonOf(it)) }
                append('}')
            }
            append('}')
        }
        append("]}}")
    }

    fun askJson(value: Boolean): String = "{\"head\":{},\"boolean\":$value}"

    /** RFC 4180 rows: variable names, then per row the IRI bare, a literal's lexical form, `_:b`, an unbound as empty. */
    fun csv(rows: Cursor, variables: List<String> = names(rows)): String = buildString {
        append(variables.joinToString(",")).append("\r\n")
        for (r in 0 until rows.size) {
            val values = rows[r].values
            for (c in variables.indices) {
                if (c > 0) append(',')
                val term = termOf(values[c]) ?: continue
                val text = if (term.type == "bnode") "_:" + term.value else term.value
                if (text.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) append('"').append(text.replace("\"", "\"\"")).append('"') else append(text)
            }
            append("\r\n")
        }
    }

    /** `?v` headers, then terms in N-Triples syntax, tab-separated, an unbound as empty. */
    fun tsv(rows: Cursor, variables: List<String> = names(rows)): String = buildString {
        append(variables.joinToString("\t") { "?$it" }).append('\n')
        for (r in 0 until rows.size) {
            val values = rows[r].values
            for (c in variables.indices) {
                if (c > 0) append('\t')
                append(lexemeOf(values[c]) ?: continue)
            }
            append('\n')
        }
    }

    /** One binding: `type` is `uri`, `literal` or `bnode`; `lang`/`datatype` as the term carries them. */
    class Binding(val type: String, val value: String, val lang: String? = null, val datatype: String? = null)

    /** The cell as a binding, or null when unbound. */
    fun termOf(cell: Any?): Binding? = when (cell) {
        null -> null
        is Boolean -> Binding("literal", cell.toString(), datatype = RdfVocab.XSD + "boolean")
        is Int, is Long, is Short, is Byte -> Binding("literal", cell.toString(), datatype = RdfVocab.XSD + "integer")
        is Number -> Binding("literal", cell.toString(), datatype = RdfVocab.XSD + "double")
        else -> cell.toString().let { text ->
            if (!isRdfTerm(text)) Binding("literal", text) else when (val term = TurtleRdf.parseTerm(text)) {
                is RdfTerm.Iri -> Binding("uri", term.iri)
                is RdfTerm.BlankNode -> Binding("bnode", term.id)
                is RdfTerm.Literal -> Binding("literal", term.lexical, term.lang, term.datatype)
                null -> Binding("literal", text)
            }
        }
    }

    /** The cell as an N-Triples term, or null when unbound. */
    fun lexemeOf(cell: Any?): String? = when (cell) {
        null -> null
        is Boolean -> "\"$cell\"^^<${RdfVocab.XSD}boolean>"
        is Int, is Long, is Short, is Byte -> "\"$cell\"^^<${RdfVocab.XSD}integer>"
        is Number -> "\"$cell\"^^<${RdfVocab.XSD}double>"
        else -> cell.toString().let { text -> if (text.startsWith("<") || text.startsWith("\"") || text.startsWith("_:")) text else RdfTerm.Literal(text).toTurtle() }
    }

    fun names(rows: Cursor): List<String> = if (rows.size == 0) emptyList() else rows.columnNames.let { n -> List(n.size) { n[it].toString() } }
}
