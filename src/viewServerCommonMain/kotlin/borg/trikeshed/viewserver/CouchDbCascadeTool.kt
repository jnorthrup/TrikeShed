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

    private val views = mapOf(
        "byOrganization" to listOf("organization_id", "machine_id"),
        "byMachine" to listOf("machine_id"),
        "byInfrastructure" to listOf("infrastructure_id", "machine_id"),
        "byContract" to listOf("contract_id", "machine_id"),
        "byBillingGroup" to listOf("billing_group_id", "machine_id"),
    )

    private val metrics = listOf(
        "interval",
        "reading_date",
        "cpu_mhz",
        "memory_mib",
        "storage_gib",
        "disk_io_kilobytes_per_sec",
        "lan_io_kilobits_per_sec",
        "wan_io_kilobits_per_sec",
        "consumption_wac",
        "created_at",
    )

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
