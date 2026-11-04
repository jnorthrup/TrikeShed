package borg.trikeshed.lcnc

import borg.trikeshed.cursor.IOMemento
import borg.trikeshed.forge.sheet.confixCellSheets
import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.*
import borg.trikeshed.parse.confix.*

/** Consume copied JSON geometry; no scanner is entered on this path. */
object LcncConfixSelection {
    /** JSON numbers cannot be decoded as CBOR solely from their first byte. */
    fun scalar(cell: ConfixCell): Any? = when (cell.row.tag) {
        IOMemento.IoLong -> requireNotNull(cell.src.spanLongOrNull(cell.row.open, cell.row.close)) { "Invalid JSON integer" }
        IOMemento.IoDouble -> requireNotNull(cell.src.spanStr(cell.row.open, cell.row.close).toDoubleOrNull()?.takeIf { it.isFinite() }) { "Invalid JSON number" }
        IOMemento.IoString -> decodeTextSpan(cell.src, cell.row.open + 1, cell.row.close - 1)
        IOMemento.IoNothing -> { require(cell.src.spanStr(cell.row.open, cell.row.close) == "null"); null }
        IOMemento.IoBoolean -> when (cell.src.spanStr(cell.row.open, cell.row.close)) {
            "true" -> true; "false" -> false; else -> error("Invalid JSON boolean")
        }
        else -> error("JSON scalar required")
    }
    fun indexed(json: String, rows: Any?, sourceCid: Any?, ok: Any?, format: Any?): ConfixDoc {
        require(ok == true && format == "JSON") { "Selection requires a successful JSON scan" }
        val bytes = json.encodeToByteArray()
        require(bytes.size in 1..65536 && sourceCid == ContentId.of(bytes).value) { "Scan source identity mismatch" }
        val supplied = requireNotNull(rows as? List<*>) { "Scan rows must be a list" }
        require(supplied.size in 1..16384) { "Scan row count outside bounds" }
        fun integer(value: Any?, max: Int): Int {
            val n = requireNotNull(value as? Number) { "Scan geometry must use integers" }.toDouble()
            require(n.isFinite() && n >= 0 && n <= max && n % 1.0 == 0.0) { "Scan geometry outside bounds" }
            return n.toInt()
        }
        val opens = IntArray(supplied.size)
        val closes = IntArray(supplied.size)
        val depths = IntArray(supplied.size)
        val tags = arrayOfNulls<IOMemento>(supplied.size)
        val children = arrayOfNulls<IntArray>(supplied.size)
        val parents = IntArray(supplied.size) { -1 }
        var edges = 0
        for (i in supplied.indices) {
            val row = requireNotNull(supplied[i] as? Map<*, *>) { "Scan row must be an object" }
            opens[i] = integer(row["open"], bytes.lastIndex)
            closes[i] = integer(row["close"], bytes.lastIndex)
            depths[i] = integer(row["depth"], 64)
            require(closes[i] >= opens[i] && (i == 0 || opens[i] > opens[i - 1])) { "Scan spans are not ordered" }
            tags[i] = when (row["tag"]) {
                "IoNothing" -> IOMemento.IoNothing
                "IoBoolean" -> IOMemento.IoBoolean
                "IoLong" -> IOMemento.IoLong
                "IoDouble" -> IOMemento.IoDouble
                "IoString" -> IOMemento.IoString
                "IoObject" -> IOMemento.IoObject
                "IoArray" -> IOMemento.IoArray
                else -> error("Unsupported JSON scan tag")
            }
            val kids = requireNotNull(row["children"] as? List<*>) { "Scan children must be a list" }
            edges += kids.size
            require(edges < supplied.size) { "Scan has too many child edges" }
            children[i] = IntArray(kids.size) { k -> integer(kids[k], supplied.lastIndex).also { child ->
                require(child > i && parents[child] == -1 && (k == 0 || child > integer(kids[k - 1], supplied.lastIndex))) {
                    "Scan child order or parentage is invalid"
                }
                parents[child] = i
            } }
        }
        require(depths[0] == 0 && parents[0] == -1 && edges == supplied.size - 1) { "Scan must have one complete root" }
        for (i in supplied.indices) {
            val kids = checkNotNull(children[i])
            if (i > 0) {
                val p = parents[i]
                require(p >= 0 && depths[i] == depths[p] + 1 && opens[i] > opens[p] && closes[i] < closes[p]) {
                    "Scan child is outside its parent"
                }
            }
            for (k in 1 until kids.size) require(opens[kids[k]] > closes[kids[k - 1]]) { "Scan siblings overlap" }
            val first = bytes[opens[i]].toInt().toChar()
            val last = bytes[closes[i]].toInt().toChar()
            when (tags[i]) {
                IOMemento.IoObject -> {
                    require(first == '{' && last == '}' && kids.size % 2 == 0) { "Invalid JSON object geometry" }
                    for (k in kids.indices step 2) require(tags[kids[k]] == IOMemento.IoString) { "JSON object key is not a string" }
                }
                IOMemento.IoArray -> require(first == '[' && last == ']') { "Invalid JSON array geometry" }
                else -> {
                    require(kids.isEmpty()) { "Scalar scan row has children" }
                    if (tags[i] == IOMemento.IoString) require(first == '"' && last == '"' && closes[i] > opens[i]) {
                        "Invalid JSON string geometry"
                    }
                }
            }
        }
        val flat = Syntax.FlatIndex(supplied.size j { opens[it] j closes[it] },
            supplied.size j { checkNotNull(tags[it]) }, depths.size j { depths[it] },
            { i -> checkNotNull(children[i]).let { xs -> xs.size j { xs[it] } } })
        val source: Series<Byte> = bytes.size j { bytes[it] }
        return Syntax.JSON.indexed(flat, source) j source
    }

    fun select(json: String, path: String, rows: Any?, sourceCid: Any?, ok: Any?, format: Any?): Map<String, Any?> {
        var cell = indexed(json, rows, sourceCid, ok, format).rootCell
        for (segment in path.trim().removePrefix("/").replace('/', '.').split('.').filter(String::isNotEmpty)) {
            cell = cell?.let { parent -> when (parent.row.tag) {
                IOMemento.IoObject -> parent[segment]
                IOMemento.IoArray -> segment.toIntOrNull()?.takeIf { it >= 0 }?.let { parent[it] }
                else -> null
            } }
        }
        fun value(cell: ConfixCell): Any? = when (cell.row.tag) {
            IOMemento.IoObject -> {
                val result = linkedMapOf<String, Any?>()
                val kids = cell.cellKids
                for (k in 0 until kids.size step 2) {
                    val key = kids[k].reify() as String
                    require(key !in result) { "Duplicate JSON key in selected value" }
                    result[key] = value(kids[k + 1])
                }
                result
            }
            IOMemento.IoArray -> (cell.cellKids α { value(it) }).toList()
            else -> scalar(cell)
        }
        val family = cell?.let { confixCellSheets("confix/$path", path, it, scalar = ::scalar) }.orEmpty()
        return _m["found" j (cell != null), "path" j path, "value" j cell?.let(::value),
            "geometry" j "native", "sourceCid" j sourceCid,
            "byteSpan" j cell?.let { _m["open" j it.row.open, "close" j it.row.close, "unit" j "utf8-byte-inclusive"] },
            "sheets" j family.map { it.toLcncMap() }, "sheet" j family.firstOrNull()?.toLcncMap()]
    }
}
