package borg.trikeshed.viewserver

/**
 * CouchDB design document for kline time-series data.
 *
 * Mirrors couchdbcascade `_design/readings`:
 *   - Map functions emit hierarchical time keys for range queries.
 *   - Reduce aggregates OHLCV fields with sum/avg/min/max (same structure as cascade readings).
 *
 * Cascade analogy:
 *   readings.byMachine      → klines.bySymbol   [symbol, timespan, year, month, day, hour, min]
 *   readings.byOrganization → klines.byTimespan  [timespan, symbol, year, month, day, hour, min]
 *   readings.byBillingGroup → klines.byDate      [year, month, day, hour, min, symbol, timespan]
 *
 * Register locally with [ReactorCouchServer.registerKlineViews] or push to a remote
 * CouchDB instance with [CouchSyncEngine.registerKlineViews].
 */
object KlineDesignDoc {

    const val DESIGN_ID = "_design/klines"
    const val DEFAULT_DB = "klines"

    /**
     * Aggregate OHLCV numeric fields — mirrors cascade byMachine/byOrganization reduce.
     * Handles both first-pass (rereduce=false) and re-reduction (rereduce=true).
     */
    private val OHLCV_REDUCE = """
function reduce(keys, values, rereduce) {
  var fields = ["open","high","low","close","volume","quoteAssetVolume","trades",
                "takerBuyBaseVolume","takerBuyQuoteVolume"];
  var o = {};
  for (var i = 0; i < fields.length; i++) o[fields[i]] = {};
  if (!rereduce) {
    var length = values.length;
    for (var i = 0; i < fields.length; i++) {
      var k = fields[i];
      var vals = values.map(function(v) { return v[k]; }).filter(function(v) { return v != null; });
      if (!vals.length) continue;
      o[k].sum = sum(vals);
      o[k].avg = o[k].sum / vals.length;
      o[k].min = Math.min.apply(null, vals);
      o[k].max = Math.max.apply(null, vals);
    }
    return [o, length];
  }
  var c = sum(values.map(function(v) { return v[1]; }));
  for (var i = 0; i < fields.length; i++) {
    var k = fields[i];
    o[k].sum = sum(values.map(function(v) { return v[0][k] ? v[0][k].sum : 0; }));
    o[k].avg = c > 0 ? o[k].sum / c : 0;
    o[k].min = Math.min.apply(null, values.map(function(v) { return v[0][k] ? v[0][k].min : Infinity; }));
    o[k].max = Math.max.apply(null, values.map(function(v) { return v[0][k] ? v[0][k].max : -Infinity; }));
  }
  return [o, c];
}""".trim()

    /** Primary kline selector — cascade byMachine analogue.
     *  Key: [symbol, timespan, year, month, day, hour, minute] */
    val MAP_BY_SYMBOL = """
function(doc) {
  if (!doc.openTime || !doc.symbol) return;
  var d = new Date(doc.openTime);
  emit([doc.symbol, doc.timespan,
        d.getUTCFullYear(), d.getUTCMonth()+1, d.getUTCDate(),
        d.getUTCHours(), d.getUTCMinutes()], doc);
}""".trim()

    /** Cross-symbol timespan selector — cascade byOrganization analogue.
     *  Key: [timespan, symbol, year, month, day, hour, minute] */
    val MAP_BY_TIMESPAN = """
function(doc) {
  if (!doc.openTime || !doc.timespan) return;
  var d = new Date(doc.openTime);
  emit([doc.timespan, doc.symbol,
        d.getUTCFullYear(), d.getUTCMonth()+1, d.getUTCDate(),
        d.getUTCHours(), d.getUTCMinutes()], doc);
}""".trim()

    /** Chronological range selector — cascade byBillingGroup/byContract analogue.
     *  Key: [year, month, day, hour, minute, symbol, timespan] */
    val MAP_BY_DATE = """
function(doc) {
  if (!doc.openTime) return;
  var d = new Date(doc.openTime);
  emit([d.getUTCFullYear(), d.getUTCMonth()+1, d.getUTCDate(),
        d.getUTCHours(), d.getUTCMinutes(), doc.symbol, doc.timespan], doc);
}""".trim()

    /** All view definitions: name → (mapSrc, reduceSrc?). */
    val views: Map<CharSequence, Pair<CharSequence, CharSequence?>> = mapOf(
        "bySymbol"   to (MAP_BY_SYMBOL   to OHLCV_REDUCE),
        "byTimespan" to (MAP_BY_TIMESPAN to OHLCV_REDUCE),
        "byDate"     to (MAP_BY_DATE     to null),
    )

    /** Register all cascade kline map+reduce views with [server] under [db]. */
    fun registerWith(server: ReactorCouchServer, db: CharSequence = DEFAULT_DB) {
        for ((name, pair) in views) {
            server.registerView(db, "klines", name, pair.first, pair.second)
        }
    }

    /** Emit the design document as a CouchDB-compatible JSON string. */
    fun toJson(): CharSequence = buildString {
        append("""{"_id":"$DESIGN_ID","language":"javascript","views":{""")
        views.entries.forEachIndexed { i, (name, pair) ->
            if (i > 0) append(',')
            val (map, reduce) = pair
            append("\"$name\":{\"map\":${JsonSerializer.serializeValue(map)}")
            if (reduce != null) append(",\"reduce\":${JsonSerializer.serializeValue(reduce)}")
            append('}')
        }
        append("}}")
    }
}
