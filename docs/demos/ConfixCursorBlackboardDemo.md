# Confix ↔ Cursor Blackboard Demo: Trading Signals

This demo illustrates the Confix ↔ Cursor bridge using **trading signals** as the problem domain.

## The Data: Trading Signal

A trading signal has these fields:

| Field | Type | Description |
|-------|------|-------------|
| `symbol` | String | e.g., "BTCUSDT" |
| `timestamp` | Long | Unix millis |
| `signal` | Enum | ENTER_LONG, ENTER_SHORT, EXIT_LONG, EXIT_SHORT, HOLD |
| `confidence` | Double | 0.0-1.0 |
| `price` | Double | Entry price |
| `rsi` | Double | RSI indicator value |
| `volume` | Double | Trading volume |

---

## Side A: Confix (Indexed + Raw Bytes)

Confix stores each signal as:
- **Tags**: field names (`symbol`, `signal`, `price`, ...)
- **Spans**: byte offsets into raw source
- **Depths**: nesting level for nested structures
- **Raw bytes**: the serialized values

```kotlin
// ConfixDoc for one signal
val signalDoc: ConfixDoc = ConfixDoc(
    index = ConfixIndex(
        tags = listOf(
            IOMemento.IoString("symbol"),
            IOMemento.IoString("signal"),
            IOMemento.IoLong,
            IOMemento.IoDouble,
            IOMemento.IoDouble,
            IOMemento.IoDouble
        ),
        spans = listOf(
            Twin(0, 7),    // "BTCUSDT" → bytes 0-7
            Twin(8, 18),  // "ENTER_LONG" → bytes 8-18
            Twin(19, 27), // 1700000000000L → bytes 19-27
            Twin(28, 36), // 0.95 → bytes 28-36
            Twin(37, 45), // 42000.0 → bytes 37-45
            Twin(46, 54)  // 1250000000.0 → bytes 46-54
        ),
        depths = listOf(0, 0, 0, 0, 0, 0)
    ),
    src = "BTCUSDTENTER_LONG17000000000000.950000042000.001250000000".toByteArray().series()
)
```

**Visual** (Confix as indexed tags → spans → bytes):

```
┌─────────────────────────────────────────────────────────────────┐
│  CONFIX (indexed)                                                │
│  Tags:     [symbol] [signal]    [ts]   [conf] [price] [volume] │
│            ↓        ↓           ↓       ↓     ↓      ↓             │
│  Spans:    0────7  8──────18  19──27  28─36 37─45 46─54       │
│            └──────────┬──────────┬─────┬─────┬─────┬           │
│                        │          │     │     │     │           │
│  Raw bytes:  "BTCUSDT"│"ENTER_L..│..L..│..0.95│..42000│..1.25e9  │
└─────────────────────────────────────────────────────────────────┘
```

---

## Side B: Cursor (Materialized Rows)

Cursor materializes the same data as row-oriented structure:

```kotlin
// Cursor from Confix
val cursor: Cursor = signalDoc.toCursor()

// RowVec at index 0 (the signal row)
val row: RowVec = cursor[0]

// Column metadata
val meta: Series<ColumnMeta> = cursor.meta
// → [symbol:String, signal:String, timestamp:Long, confidence:Double, price:Double, volume:Double]

// Access values
val symbol: Any? = row[0].a  // "BTCUSDT"
val signal: Any? = row[1].a   // "ENTER_LONG"
val price: Any? = row[4].a     // 42000.0
```

**Visual** (Cursor as rows + columns):

```
┌────────────────────────────────────────────��────────────────────┐
│  CURSOR (materialized)                                          │
│  Columns:  symbol      signal      timestamp  conf   price   vol  │
│  Types:    String      String     Long       Double Double Double  │
│           ───────── ───────── ───────── ─────── ─────── ───────  │
│  Row 0:   BTCUSDT    ENTER_LONG 1700000..  0.95   42000   1.25e9  │
│  Row 1:   ETHUSDT    HOLD       1700000..  0.00   2100    8.5e8  │
│  Row 2:   SOLUSDT   EXIT_LONG   1700000..  0.72   98.50   2.1e9  │
└─────────────────────────────────────────────────────────────────┘
```

---

## The Bridge: `ConfixDoc.toCursor()`

The conversion is straightforward:

```kotlin
fun ConfixDoc.toCursor(): Cursor {
    // 1. Extract tags → ColumnMeta
    val meta: Series<ColumnMeta> = index.tags.sizes { tag ->
        ColumnMeta(
            name = tag.toString(),
            type = when (tag) {
                is IOMemento.IoString -> IOMemento.IoString
                is IOMemento.IoLong -> IOMemento.IoLong
                is IOMemento.IoDouble -> IOMemento.IoDouble
                else -> IOMemento.IoString
            }
        )
    }

    // 2. Extract spans + src → values
    val data: Series<Series<Any>> = index.spans.sizes { span ->
        val raw = src.subSeries(span.a, span.b - span.a)
        raw.reify()  // ConfixCell.reify() parses bytes → typed value
    }

    // 3. Join meta + data → RowVec → Cursor
    val metaProviders: Series<`ColumnMeta↻`> = meta.sizes { { meta[it] } }
    val rows: Series<RowVec> = data.sizes { rowData ->
        (rowData α { it as Any? }).joins(metaProviders)
    }

    return rows
}
```

---

## Demo Data: Signal Batch

```
# Signals (Confix form — compact, indexed)
symbol: BTCUSDT, signal: ENTER_LONG,  ts: 1700000000000, conf: 0.95,  price: 42000,  vol: 1.25e9
symbol: ETHUSDT, signal: HOLD,       ts: 1700000001000, conf: 0.00,  price: 2100,  vol: 8.5e8
symbol: SOLUSDT, signal: EXIT_LONG,  ts: 1700000002000, conf: 0.72,  price: 98.50,  vol: 2.1e9
symbol: ARBUSDT, signal: ENTER_SHORT, ts: 1700000003000, conf: 0.88,  price: 1.12,  vol: 5.7e8
symbol: MATICDT, signal: HOLD,       ts: 1700000004000, conf: 0.00,  price: 0.85,  vol: 3.2e9
```

```
# Signals (Cursor form — materialized rows)
┌──────────┬─────────────┬────────────────┬──────┬────────┬─────────┐
│ symbol   │ signal     │ timestamp      │ conf │ price  │ volume  │
├──────────┼────────────┼────────────────┼──────┼────────┼─────────┤
│ BTCUSDT  │ ENTER_LONG │ 1700000000000  │ 0.95 │ 42000  │ 1.25e9  │
│ ETHUSDT  │ HOLD       │ 1700000001000  │ 0.00 │ 2100   │ 8.5e8   │
│ SOLUSDT  │ EXIT_LONG  │ 1700000002000  │ 0.72 │ 98.50  │ 2.1e9   │
│ ARBUSDT  │ ENTER_SH..│ 1700000003000  │ 0.88 │ 1.12   │ 5.7e8   │
│ MATICDT  │ HOLD       │ 1700000004000  │ 0.00 │ 0.85   │ 3.2e9   │
└──────────┴────────────┴────────────────┴──────┴────────┴─────────┘
```

---

## Why This Domain Works

| Confix strength | Trading signal use |
|-----------------|-------------------|
| Compact storage | Millions of signals → minimal byte overhead |
| Tagged spans | Random access by field name |
| Depth tracking | Nested arrays (e.g., multi-timeframe signals) |

| Cursor strength | Trading signal use |
|-----------------|-------------------|
| Columnar access | Scan all `price` values, compute returns |
| Row iteration | Emit signals to downstream |
| Metadata | Preserve type info for tensor conversion |

**The bridge is the join point**: `RowVec` appears in both as the row shape.

---

## Run the Demo

```bash
cd TrikeShed
./gradlew :jvmTest --tests "borg.trikeshed.confix.ConfixCursorDemoTest"
```

Or interact in REPL:

```kotlin
val doc = ConfixDoc.parse(signalConfixText)
val cursor = doc.toCursor()
cursor[0][4].a  // → 42000.0 (price)
```