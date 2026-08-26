package borg.trikeshed.pdf

/**
 * Text extraction over a disassembled document: walk /Catalog → /Pages →
 * /Kids to each /Page, decode its /Contents, and fold the text-showing
 * operators (Tj, TJ, ', ") with the page's font mapping.
 *
 * Encoding honesty: a font WITH /ToUnicode gets its CMap honored (bfchar +
 * bfrange, 1- and 2-byte codes); a simple font without one falls back to
 * Latin-1 (right for the WinAnsi core range); a composite font without
 * ToUnicode maps to glyph ids, not Unicode — those strings are SKIPPED rather
 * than emitted as garbage, and counted in [Extraction.unmappable].
 */
object PdfText {

    class Extraction(val text: String, val pages: Int, val unmappable: Int)

    fun extract(doc: PdfDocument): Extraction {
        val sb = StringBuilder()
        var pages = 0
        var unmappable = 0
        val pageDicts = collectPages(doc)
        for (page in pageDicts) {
            pages++
            val fonts = fontMaps(doc, page)
            val content = contentBytes(doc, page) ?: continue
            unmappable += renderContent(content, fonts, sb)
            sb.append('\n')
        }
        return Extraction(sb.toString().trim(), pages, unmappable)
    }

    // ── page tree ─────────────────────────────────────────────────────

    private fun collectPages(doc: PdfDocument): List<PdfObject.PDict> {
        val out = ArrayList<PdfObject.PDict>()
        val seen = HashSet<PdfObject.PDict>()
        fun walk(node: PdfObject.PDict?, depth: Int) {
            if (node == null || depth > 64 || !seen.add(node)) return
            when ((node["Type"] as? PdfObject.PName)?.value) {
                "Page" -> out.add(node)
                else -> {
                    val kids = doc.resolve(node["Kids"]) as? PdfObject.PArr ?: return
                    for (k in kids.items) walk(doc.dict(k), depth + 1)
                }
            }
        }
        val root = doc.dict(doc.catalog?.get("Pages"))
        if (root != null) walk(root, 0)
        if (out.isEmpty()) {
            // torn tree: any /Type /Page object counts
            for (o in doc.objects.values) {
                val d = o as? PdfObject.PDict ?: continue
                if ((d["Type"] as? PdfObject.PName)?.value == "Page") out.add(d)
            }
        }
        return out
    }

    private fun contentBytes(doc: PdfDocument, page: PdfObject.PDict): ByteArray? {
        val parts = ArrayList<ByteArray>()
        fun add(o: PdfObject?) {
            when (val r = doc.resolve(o)) {
                is PdfObject.PStream -> r.decoded?.let(parts::add)
                is PdfObject.PArr -> r.items.forEach(::add)
                else -> {}
            }
        }
        add(page["Contents"])
        if (parts.isEmpty()) return null
        val total = parts.sumOf { it.size } + parts.size
        val out = ByteArray(total)
        var w = 0
        for (p in parts) { p.copyInto(out, w); w += p.size; out[w++] = '\n'.code.toByte() }
        return out
    }

    // ── fonts + ToUnicode CMaps ───────────────────────────────────────

    /** code (1- or 2-byte) → string; null map = simple Latin-1 fallback; twoByte marks composite. */
    class FontMap(val toUnicode: Map<Int, String>?, val twoByte: Boolean, val composite: Boolean)

    private fun fontMaps(doc: PdfDocument, page: PdfObject.PDict): Map<String, FontMap> {
        val res = doc.dict(page["Resources"]) ?: return emptyMap()
        val fonts = doc.dict(res["Font"]) ?: return emptyMap()
        val out = HashMap<String, FontMap>()
        for ((name, ref) in fonts.entries) {
            val fd = doc.dict(ref) ?: continue
            val subtype = (fd["Subtype"] as? PdfObject.PName)?.value
            val composite = subtype == "Type0"
            val cmapStream = doc.resolve(fd["ToUnicode"]) as? PdfObject.PStream
            val cmap = cmapStream?.decoded?.let(::parseCMap)
            out[name] = FontMap(cmap, composite || (cmap?.keys?.any { it > 0xFF } == true), composite)
        }
        return out
    }

    /** bfchar + bfrange (incl. array-destination form). Codes up to 2 bytes. */
    fun parseCMap(bytes: ByteArray): Map<Int, String> {
        val lx = PdfLexer(bytes)
        val map = HashMap<Int, String>()
        val pending = ArrayList<PdfObject>()
        while (lx.pos < lx.end) {
            val o = lx.parseObject() ?: break
            if (o is PdfObject.PName) {
                when (o.value) {
                    "beginbfchar" -> {
                        pending.clear()
                        while (true) {
                            val e = lx.parseObject() ?: break
                            if (e is PdfObject.PName && e.value == "endbfchar") break
                            pending.add(e)
                        }
                        var k = 0
                        while (k + 1 < pending.size) {
                            val src = pending[k] as? PdfObject.PStr
                            val dst = pending[k + 1] as? PdfObject.PStr
                            if (src != null && dst != null) map[codeOf(src)] = utf16(dst)
                            k += 2
                        }
                    }
                    "beginbfrange" -> {
                        pending.clear()
                        while (true) {
                            val e = lx.parseObject() ?: break
                            if (e is PdfObject.PName && e.value == "endbfrange") break
                            pending.add(e)
                        }
                        var k = 0
                        while (k + 2 < pending.size) {
                            val lo = pending[k] as? PdfObject.PStr
                            val hi = pending[k + 1] as? PdfObject.PStr
                            val dst = pending[k + 2]
                            if (lo != null && hi != null) {
                                val a = codeOf(lo); val b = codeOf(hi)
                                when (dst) {
                                    is PdfObject.PStr -> {
                                        val base = utf16(dst)
                                        val cp = base.lastOrNull()?.code ?: 0
                                        for (c in a..minOf(b, a + 0xFFFF)) {
                                            map[c] = if (base.length <= 1) (cp + (c - a)).toChar().toString()
                                            else base.dropLast(1) + (cp + (c - a)).toChar()
                                        }
                                    }
                                    is PdfObject.PArr -> for ((idx, item) in dst.items.withIndex()) {
                                        (item as? PdfObject.PStr)?.let { map[a + idx] = utf16(it) }
                                    }
                                    else -> {}
                                }
                            }
                            k += 3
                        }
                    }
                }
            }
        }
        return map
    }

    private fun codeOf(s: PdfObject.PStr): Int {
        var v = 0
        for (b in s.bytes) v = (v shl 8) or (b.toInt() and 0xFF)
        return v
    }

    private fun utf16(s: PdfObject.PStr): String {
        val b = s.bytes
        if (b.size % 2 != 0) return s.latin1
        val sb = StringBuilder(b.size / 2)
        var i = 0
        while (i + 1 < b.size) {
            sb.append((((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)).toChar())
            i += 2
        }
        return sb.toString()
    }

    // ── content operators ─────────────────────────────────────────────

    /** Fold Tj/TJ/'/" into [sb]; returns count of strings skipped as unmappable. */
    private fun renderContent(content: ByteArray, fonts: Map<String, FontMap>, sb: StringBuilder): Int {
        val lx = PdfLexer(content)
        val stack = ArrayList<PdfObject>(8)
        var font: FontMap? = null
        var skipped = 0

        fun show(str: PdfObject.PStr) {
            val f = font
            when {
                f?.toUnicode != null -> {
                    val bytes = str.bytes
                    if (f.twoByte) {
                        var i = 0
                        while (i + 1 < bytes.size) {
                            val c = ((bytes[i].toInt() and 0xFF) shl 8) or (bytes[i + 1].toInt() and 0xFF)
                            sb.append(f.toUnicode[c] ?: "")
                            i += 2
                        }
                    } else for (b in bytes) sb.append(f.toUnicode[b.toInt() and 0xFF] ?: "")
                }
                f?.composite == true -> skipped++      // glyph ids without a CMap: refuse garbage
                else -> sb.append(str.latin1)          // simple font fallback
            }
        }

        while (lx.pos < lx.end) {
            val o = lx.parseObject() ?: break
            if (o !is PdfObject.PName) { stack.add(o); if (stack.size > 16) stack.removeAt(0); continue }
            when (o.value) {
                "Tf" -> {
                    // operand order: /FontName size Tf — the name is second-from-top
                    val nameOp = stack.getOrNull(stack.size - 2) as? PdfObject.PName
                    font = nameOp?.let { fonts[it.value] }
                }
                "Tj" -> (stack.lastOrNull() as? PdfObject.PStr)?.let(::show)
                "'" -> { sb.append('\n'); (stack.lastOrNull() as? PdfObject.PStr)?.let(::show) }
                "\"" -> { sb.append('\n'); (stack.lastOrNull() as? PdfObject.PStr)?.let(::show) }
                "TJ" -> (stack.lastOrNull() as? PdfObject.PArr)?.items?.forEach { item ->
                    when (item) {
                        is PdfObject.PStr -> show(item)
                        is PdfObject.PNum -> if (item.value < -300) sb.append(' ')   // big negative kern ≈ word gap (ordinary optical kerning rarely exceeds ~200)
                        else -> {}
                    }
                }
                "T*" -> sb.append('\n')
                "Td", "TD" -> {
                    val dy = (stack.lastOrNull() as? PdfObject.PNum)?.value ?: 0.0
                    if (dy != 0.0) sb.append('\n') else sb.append(' ')
                }
                "ET" -> sb.append('\n')
                else -> {}
            }
            stack.clear()
        }
        return skipped
    }
}
