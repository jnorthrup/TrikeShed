package borg.trikeshed.viewserver

import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

fun interface ViewServerMapper {
    fun map(document: Map<String, ViewValue>): List<ViewEmission>
}

/** Pluggable mapper/reducer capability owned by the view server. */
interface ViewServerTool {
    val id: String
    fun mapper(name: String): ViewServerMapper
    fun reduce(values: List<ViewValue>): ViewValue
    fun rereduce(values: List<ViewValue>): ViewValue
}

/** Mapper and reducer ported from the sibling couchdbcascade design document. */
object CouchDbCascadeTool : ViewServerTool {
    override val id: String = "couchdbcascade"

    // The vocabulary is shared with the couch view path's `ReduceFunction.Cascade` and the jvm
    // JS generator; it lives in one place so the three cannot disagree about what a cascade is.
    private val views get() = borg.trikeshed.couch.CouchCascade.VIEWS
    private val metrics get() = borg.trikeshed.couch.CouchCascade.METRICS

    override fun mapper(name: String): ViewServerMapper {
        val keyFields = views[name]
            ?: throw IllegalArgumentException("unknown CouchDB Cascade view: $name")
        return ViewServerMapper { document ->
            val timestamp = (document["reading_date"] as? ViewValue.Text)?.value
                ?: throw IllegalArgumentException("cascade document missing reading_date")
            val utc = Instant.parse(timestamp).toLocalDateTime(TimeZone.UTC)
            val key = ViewValue.ArrayValue(
                keyFields.map { document[it] ?: ViewValue.Null } + listOf(
                    ViewValue.Number(utc.year.toDouble()),
                    ViewValue.Number((utc.month.ordinal + 1).toDouble()),
                    ViewValue.Number(utc.day.toDouble()),
                    ViewValue.Number(utc.hour.toDouble()),
                    ViewValue.Number(utc.minute.toDouble()),
                ),
            )
            listOf(ViewEmission(key, ViewValue.ObjectValue(document)))
        }
    }

    override fun reduce(values: List<ViewValue>): ViewValue {
        val documents = values.mapNotNull { it as? ViewValue.ObjectValue }
        val count = documents.size.toDouble()
        val rollup = metrics.associateWith { field ->
            val numbers = documents.mapNotNull { (it.fields[field] as? ViewValue.Number)?.value }
            val sum = numbers.sum()
            ViewValue.ObjectValue(
                mapOf(
                    "sum" to ViewValue.Number(sum),
                    "avg" to ViewValue.Number(if (count == 0.0) 0.0 else sum / count),
                    "min" to ViewValue.Number(numbers.minOrNull() ?: 0.0),
                    "max" to ViewValue.Number(numbers.maxOrNull() ?: 0.0),
                ),
            )
        }
        return ViewValue.ArrayValue(listOf(ViewValue.ObjectValue(rollup), ViewValue.Number(count)))
    }

    override fun rereduce(values: List<ViewValue>): ViewValue {
        val partials = values.mapNotNull { it as? ViewValue.ArrayValue }
        val totalCount = partials.sumOf { it.values.getOrNull(1).numberOrZero() }
        val rollup = metrics.associateWith { field ->
            val fieldParts = partials.mapNotNull { partial ->
                val allMetrics = partial.values.getOrNull(0) as? ViewValue.ObjectValue
                allMetrics?.fields?.get(field) as? ViewValue.ObjectValue
            }
            val sum = fieldParts.sumOf { it.fields["sum"].numberOrZero() }
            val mins = fieldParts.mapNotNull { (it.fields["min"] as? ViewValue.Number)?.value }
            val maxes = fieldParts.mapNotNull { (it.fields["max"] as? ViewValue.Number)?.value }
            ViewValue.ObjectValue(
                mapOf(
                    "sum" to ViewValue.Number(sum),
                    "avg" to ViewValue.Number(if (totalCount == 0.0) 0.0 else sum / totalCount),
                    "min" to ViewValue.Number(mins.minOrNull() ?: 0.0),
                    "max" to ViewValue.Number(maxes.maxOrNull() ?: 0.0),
                ),
            )
        }
        return ViewValue.ArrayValue(listOf(ViewValue.ObjectValue(rollup), ViewValue.Number(totalCount)))
    }
}

private fun ViewValue?.numberOrZero(): Double =
    (this as? ViewValue.Number)?.value ?: 0.0
