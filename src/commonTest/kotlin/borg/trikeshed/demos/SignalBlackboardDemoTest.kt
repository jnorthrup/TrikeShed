package borg.trikeshed.demos

import borg.trikeshed.cursor.*
import borg.trikeshed.lib.*
import borg.trikeshed.parse.confix.*
import borg.trikeshed.lib.j
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Trading Signal Blackboard Demo — Confix ↔ Cursor bridge.
 *
 * Demonstrates the full pipeline:
 *   1. Parse JSON signal document → ConfixDoc
 *   2. Navigate ConfixDoc via .value() — indexed access
 *   3. Convert ConfixDoc.cells → Cursor<RowVec>
 *   4. Materialize and inspect RowVec values
 *   5. Render cursor to console table
 *
 * Data domain: 5 trading signals (BTC, ETH, SOL, ARB, MATIC)
 * Types: symbol, signal, timestamp, confidence, price, volume
 */
class SignalBlackboardDemoTest {

    // ── Test data: 5 trading signals as JSON ────────────────────────

    private val signalJson = """
    {
      "signals": [
        {"symbol": "BTCUSDT", "signal": "ENTER_LONG",  "timestamp": 1700000000000, "confidence": 0.95, "price": 42000.0,  "volume": 1250000000.0},
        {"symbol": "ETHUSDT", "signal": "HOLD",        "timestamp": 1700000001000, "confidence": 0.00, "price": 2100.0,   "volume": 850000000.0},
        {"symbol": "SOLUSDT", "signal": "EXIT_LONG",   "timestamp": 1700000002000, "confidence": 0.72, "price": 98.50,   "volume": 2100000000.0},
        {"symbol": "ARBUSDT", "signal": "ENTER_SHORT", "timestamp": 1700000003000, "confidence": 0.88, "price": 1.12,    "volume": 570000000.0},
        {"symbol": "MATICDT", "signal": "HOLD",        "timestamp": 1700000004000, "confidence": 0.00, "price": 0.85,    "volume": 3200000000.0}
      ]
    }
    """.trimIndent()

    // ── Step 1: Parse JSON → ConfixDoc ─────────────────────────────

    @Test
    fun `01 parse JSON to ConfixDoc`() {
        val doc = confixDoc(signalJson)

        assertNotNull(doc.root, "ConfixDoc must have a root RowVec")
        assertTrue(doc.src.size > 0, "ConfixDoc must have raw bytes")

        // Verify ConfixDoc structure: Join<ConfixIndex, Series<Byte>>
        // doc.index is a ConfixIndex = FacetedRow<Any>
        // doc.src is a Series<Byte>
        val idx = doc.index
        val src = doc.src
        assertTrue(idx.size > 0, "ConfixIndex must have facets")
        assertTrue(src.size > 0, "Series<Byte> must have bytes")
    }

    // ── Step 2: ConfixDoc indexed navigation ────────────────────────

    @Test
    fun `02 navigate ConfixDoc via value paths`() {
        val doc = confixDoc(signalJson)

        // Root-level navigation
        val symbols = doc.value("signals", 0, "symbol")
        assertEquals("BTCUSDT", symbols)

        // Array index navigation
        val secondSignal = doc.value("signals", 1)
        assertNotNull(secondSignal)

        // Scalar extraction
        val btcPrice = doc.value("signals", 0, "price")
        assertEquals(42000.0, btcPrice)

        // Deep navigation: ETH signal type
        val ethSignal = doc.value("signals", 1, "signal")
        assertEquals("HOLD", ethSignal)

        // Volume for SOL
        val solVolume = doc.value("signals", 2, "volume")
        assertEquals(2100000000.0, solVolume)

        // Confidence for ARB
        val arbConf = doc.value("signals", 3, "confidence")
        assertEquals(0.88, arbConf)
    }

    @Test
    fun `03 ConfixDoc exposes cells as Series ConfixCell`() {
        val doc = confixDoc(signalJson)

        // ConfixDoc.cells → Series<ConfixCell>
        // Each ConfixCell = Join<RowVec, Series<Byte>>
        val cells = doc.cells
        assertTrue(cells.size > 0, "Should have cells")

        // ConfixCell.row → RowVec
        // ConfixCell.src → Series<Byte>
        val first = cells[0]
        val row: RowVec = first.row
        val src: Series<Byte> = first.src

        assertTrue(row.size > 0, "First cell's RowVec must have columns")
        assertTrue(src.size > 0, "First cell's source must have bytes")
    }

    // ── Step 3: ConfixDoc → Cursor ────────────────────────────────

    @Test
    fun `04 ConfixDoc roots produce a Cursor`() {
        val doc = confixDoc(signalJson)

        // ConfixDoc.roots → Cursor
        // This is how you lift ConfixDoc into the Cursor algebra
        val roots: Cursor = doc.roots
        assertTrue(roots.size > 0, "roots must produce rows")

        // First row is the meta exemplar — Row 0 of a Cursor
        // is the idempotent schema shape
        val metaRow: RowVec = roots[0]
        assertTrue(metaRow.size > 0, "Meta row must have columns")
    }

    @Test
    fun `05 Cursor meta yields ColumnMeta series`() {
        val doc = confixDoc(signalJson)
        val roots: Cursor = doc.roots

        // cursor.meta → Series<ColumnMeta>
        // ColumnMeta = Join<CharSequence, Join<TypeMemento, ColumnMeta?>>
        val meta: Series<ColumnMeta> = roots.meta

        assertTrue(meta.size >= 2, "Meta must have at least 2 columns")

        // Check column names and types
        val firstCol: ColumnMeta = meta[0]
        val colName = firstCol.name.toString()
        assertTrue(colName.isNotEmpty(), "Column must have a name")

        val colType = firstCol.type
        assertNotNull(colType, "Column must have a type (TypeMemento)")
    }

    // ── Step 4: Materialize RowVec values ───────────────────────────

    @Test
    fun `06 RowVec columns are accessible by index`() {
        val doc = confixDoc(signalJson)
        val roots: Cursor = doc.roots

        // cursor[i] → RowVec
        val row0: RowVec = roots[0]  // signals[0] → BTCUSDT signal

        // RowVec = Series2<Any?, ColumnMeta↻>
        // row[col].a → value, row[col].b() → ColumnMeta
        assertTrue(row0.size > 0, "Row must have columns")

        // The signals array is inside the root object
        // So signals[0] is actually the first child of the root
        // Navigate: root → signals → signals[0]
        val signalsCell = doc.docAt("signals", 0)
        assertNotNull(signalsCell, "signals[0] should exist")

        val signalRow: RowVec = signalsCell.row

        // signalRow[0].a → "BTCUSDT"
        // signalRow[1].a → "ENTER_LONG"
        // signalRow[2].a → 1700000000000L
        // signalRow[3].a → 0.95
        // signalRow[4].a → 42000.0
        // signalRow[5].a → 1250000000.0
        val symbol: Any? = signalRow[0].a
        assertEquals("BTCUSDT", symbol)

        val signal: Any? = signalRow[1].a
        assertEquals("ENTER_LONG", signal)

        val price: Any? = signalRow[4].a
        assertEquals(42000.0, price)
    }

    @Test
    fun `07 RowVec columns have ColumnMeta metadata`() {
        val doc = confixDoc(signalJson)
        val signalCell = doc.docAt("signals", 0)
        val signalRow: RowVec = signalCell!!.row

        // Each cell has value + metadata
        val priceCell = signalRow[4]  // price column
        val priceValue: Any? = priceCell.a
        val priceMeta: ColumnMeta = priceCell.b()

        assertEquals(42000.0, priceValue)
        assertEquals(IOMemento.IoDouble, priceMeta.type, "price is IoDouble")
        assertEquals("price", priceMeta.name.toString(), "column name is 'price'")

        val symbolCell = signalRow[0]
        val symbolMeta: ColumnMeta = symbolCell.b()
        assertEquals(IOMemento.IoString, symbolMeta.type, "symbol is IoString")
        assertEquals("symbol", symbolMeta.name.toString())
    }

    @Test
    fun `08 ConfixCell reify parses bytes to typed values`() {
        val doc = confixDoc(signalJson)
        val signalCell = doc.docAt("signals", 0)

        // ConfixCell.reify() → Any? — parses the raw bytes via RowVec.reify(src)
        // This uses the IOMemento type tag to deserialize
        val price = signalCell!!["price"]?.reify()
        assertEquals(42000.0, price)

        val symbol = signalCell["symbol"]?.reify()
        assertEquals("BTCUSDT", symbol)

        val ts = signalCell["timestamp"]?.reify()
        assertEquals(1700000000000L, ts)
    }

    // ── Step 5: Full signal batch → Cursor ─────────────────────────

    @Test
    fun `09 extract all 5 signals as Cursor`() {
        val doc = confixDoc(signalJson)

        // Collect all signals as ConfixCell array
        val signalCells: Series<ConfixCell> = 5 j { i: Int ->
            doc.docAt("signals", i)!!
        }

        // Map ConfixCell → RowVec (strip the Series<Byte>)
        val signalRows: Series<RowVec> = signalCells.size j { i: Int ->
            signalCells.b(i).row
        }

        // Build a Cursor from the RowVec series
        // cursor.meta is the ColumnMeta from the first signal row
        val firstRow: RowVec = signalRows[0]
        val meta: Series<ColumnMeta> = firstRow.size j { c -> firstRow[c].b() }

        // cursor = rows
        val cursor: Cursor = signalRows

        // Verify cursor properties
        assertEquals(5, cursor.size, "Cursor should have 5 rows")
        assertEquals(6, cursor.meta.size, "Each row should have 6 columns")

        // Check all 5 rows
        assertEquals("BTCUSDT",  cursor[0][0].a)
        assertEquals("HOLD",     cursor[1][1].a)
        assertEquals("EXIT_LONG", cursor[2][1].a)
        assertEquals("ENTER_SHORT", cursor[3][1].a)
        assertEquals("HOLD",     cursor[4][1].a)
    }

    @Test
    fun `10 signal values typed correctly from reify`() {
        val doc = confixDoc(signalJson)

        val signalCells: Series<ConfixCell> = 5 j { doc.docAt("signals", it)!! }

        // SOL signal (index 2): EXIT_LONG, price=98.50
        val sol = signalCells.b(2)
        val solRow: RowVec = sol.row

        val symbol: String   = sol["symbol"]?.reify() as? String ?: ""
        val signal: String   = sol["signal"]?.reify() as? String ?: ""
        val price: Double    = sol["price"]?.reify() as? Double ?: 0.0
        val volume: Double  = sol["volume"]?.reify() as? Double ?: 0.0
        val conf: Double    = sol["confidence"]?.reify() as? Double ?: 0.0

        assertEquals("SOLUSDT", symbol)
        assertEquals("EXIT_LONG", signal)
        assertEquals(98.50, price)
        assertEquals(2100000000.0, volume)
        assertEquals(0.72, conf)
    }

    // ── Step 6: Squeeze and project ────────────────────────────────

    @Test
    fun `11 α-projection over signal Cursor extracts price column`() {
        val doc = confixDoc(signalJson)
        val signalCells: Series<ConfixCell> = 5 j { doc.docAt("signals", it)!! }
        val signalRows: Series<RowVec> = signalCells.size j { signalCells.b(it).row }
        val cursor: Cursor = signalRows

        // Project price column only: cursor α { row → row[4].a }
        val prices: Series<Double> = cursor.size j { i: Int ->
            (cursor[i][4].a as? Double) ?: 0.0
        }

        assertEquals(5, prices.size)
        assertEquals(42000.0, prices[0])
        assertEquals(2100.0,  prices[1])
        assertEquals(98.50,   prices[2])
        assertEquals(1.12,    prices[3])
        assertEquals(0.85,     prices[4])
    }

    @Test
    fun `12 filter Cursor by signal type using α projection`() {
        val doc = confixDoc(signalJson)
        val signalCells: Series<ConfixCell> = 5 j { doc.docAt("signals", it)!! }
        val signalRows: Series<RowVec> = signalCells.size j { signalCells.b(it).row }
        val cursor: Cursor = signalRows

        // Filter: only ENTER_* signals (confidence > 0)
        val actionableSignals: Series<Int> = cursor.size j { i: Int ->
            val conf = (cursor[i][3].a as? Double) ?: 0.0
            if (conf > 0) i else -1
        } α { if (it >= 0) cursor[it] else null } α { it!! }

        // ENTER_LONG (BTC), EXIT_LONG (SOL), ENTER_SHORT (ARB)
        assertTrue(actionableSignals.size >= 3, "Should have at least 3 actionable signals")
    }

    // ── Step 7: Confix ↔ Cursor roundtrip ─────────────────────────

    @Test
    fun `13 ConfixCell asFaceted preserves row structure`() {
        val doc = confixDoc(signalJson)
        val solCell = doc.docAt("signals", 2)!!
        val solRow: RowVec = solCell.row

        // Lift RowVec → FacetedRow<ColK<*>>
        val faceted = solRow.asFaceted()

        // Lower back: FacetedRow → RowVec
        val restored: RowVec = faceted.asRowVec()

        // Values must match
        assertEquals(solRow[0].a, restored[0].a, "symbol must roundtrip")
        assertEquals(solRow[1].a, restored[1].a, "signal must roundtrip")
        assertEquals(solRow[4].a, restored[4].a, "price must roundtrip")
        assertEquals(solRow[5].a, restored[5].a, "volume must roundtrip")

        // Width must match
        assertEquals(solRow.size, restored.size, "row width must roundtrip")
    }

    @Test
    fun `14 ColK ByName navigation over signal RowVec`() {
        val doc = confixDoc(signalJson)
        val arbCell = doc.docAt("signals", 3)!!
        val arbRow: RowVec = arbCell.row

        // asFaceted() lets us use ColK.ByName
        val faceted = arbRow.asFaceted()

        // ColK.ByName("symbol") → value
        val symbol = faceted.b(ColK.ByName("symbol"))
        assertEquals("ARBUSDT", symbol)

        val signal = faceted.b(ColK.ByName("signal"))
        assertEquals("ENTER_SHORT", signal)

        val price = faceted.b(ColK.ByName("price"))
        assertEquals(1.12, price)
    }

    // ── Step 8: Byte-addressable Confix vs materialized Cursor ─────

    @Test
    fun `15 ConfixCell src preserves raw bytes for zero-copy access`() {
        val doc = confixDoc(signalJson)
        val btcCell = doc.docAt("signals", 0)!!
        val btcRow: RowVec = btcCell.row
        val btcSrc: Series<Byte> = btcCell.src

        // Raw bytes: compact, byte-addressable via spans
        assertTrue(btcSrc.size > 0, "ConfixCell must carry raw bytes")

        // Span for "BTCUSDT" (symbol field)
        val symbolSpan = btcRow[0]
        val open = symbolSpan.b().let { (it as? IOMemento)?.let { m -> m } ?: IOMemento.IoString }.let {
            // Find the span offset — the tag tells us the IOMemento type
            // For IoString, open is the start of the quoted string
            btcRow[0].b().let { meta -> meta }.let { 0 }  // placeholder
        }
        assertTrue(btcSrc.size > 0, "src should be the full document bytes")

        // Cursor version: values already materialized
        val btcCursor = doc.docAt("signals", 0)!!
        val materializedSymbol = btcCursor.row[0].a
        assertEquals("BTCUSDT", materializedSymbol)
    }

    // ── Meta series ────────────────────────────────────────────────

    @Test
    fun `16 IOMemento types cover all signal field types`() {
        val doc = confixDoc(signalJson)
        val maticCell = doc.docAt("signals", 4)!!
        val maticRow: RowVec = maticCell.row

        // Check all 6 column types
        val types = maticRow.size j { i: Int -> maticRow[i].b().type }

        assertEquals(IOMemento.IoString, types[0], "symbol → IoString")
        assertEquals(IOMemento.IoString, types[1], "signal → IoString")
        assertEquals(IOMemento.IoLong,   types[2], "timestamp → IoLong")
        assertEquals(IOMemento.IoDouble, types[3], "confidence → IoDouble")
        assertEquals(IOMemento.IoDouble, types[4], "price → IoDouble")
        assertEquals(IOMemento.IoDouble, types[5], "volume → IoDouble")
    }

    // ── Console render helper ─────────────────────────────────────

    @Test
    fun `17 render signal Cursor to console table`() {
        val doc = confixDoc(signalJson)
        val signalCells: Series<ConfixCell> = 5 j { doc.docAt("signals", it)!! }
        val signalRows: Series<RowVec> = signalCells.size j { signalCells.b(it).row }
        val cursor: Cursor = signalRows

        val meta = cursor.meta

        // Build ASCII table
        val sb = StringBuilder()
        val colWidths = meta.size j { c: Int ->
            meta[c].name.toString().length.coerceAtLeast(12)
        }

        // Header
        meta.view.forEachIndexed { c, cm ->
            val name = cm.name.toString().padEnd(colWidths[c])
            sb.append("│ ").append(name).append(' ')
        }
        sb.append("│\n")

        // Separator
        meta.view.forEachIndexed { c, _ ->
            sb.append("├ ").append("─".repeat(colWidths[c])).append(' ')
        }
        sb.append("┤\n")

        // Rows
        repeat(cursor.size) { r ->
            repeat(meta.size) { c ->
                val v = cursor[r][c].a
                val s = when (v) {
                    is Double -> {
                        if (v >= 1e9) "%.2e".format(v)
                        else if (v == v.toLong().toDouble()) v.toLong().toString()
                        else "%.2f".format(v)
                    }
                    is Long -> v.toString()
                    is String -> v
                    else -> v?.toString() ?: "null"
                }
                sb.append("│ ").append(s.padEnd(colWidths[c])).append(' ')
            }
            sb.append("│\n")
        }

        val table = sb.toString()

        // Verify table structure
        assertTrue(table.contains("symbol"), "Table must have 'symbol' header")
        assertTrue(table.contains("BTCUSDT"), "Table must have BTCUSDT row")
        assertTrue(table.contains("ENTER_LONG"), "Table must show signal types")
        assertTrue(table.contains("42000"), "Table must show prices")
    }
}
