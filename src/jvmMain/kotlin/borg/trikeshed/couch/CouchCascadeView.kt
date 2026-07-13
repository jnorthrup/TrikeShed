package borg.trikeshed.couch

enum class CouchCascadeView(
    val viewName: String,
    private val keyFields: List<String>,
) {
    BY_ORGANIZATION("byOrganization", listOf("organization_id", "machine_id")),
    BY_MACHINE("byMachine", listOf("machine_id")),
    BY_INFRASTRUCTURE("byInfrastructure", listOf("infrastructure_id", "machine_id")),
    BY_CONTRACT("byContract", listOf("contract_id", "machine_id")),
    BY_BILLING_GROUP("byBillingGroup", listOf("billing_group_id", "machine_id"));

    internal val mapSource: String
        get() {
            val dimensions = keyFields.joinToString(",\n        ") { "doc.$it" }
            return """
                function (doc) {
                  var d = new Date(doc.reading_date);
                  emit([
                    $dimensions,
                    d.getUTCFullYear(),
                    d.getUTCMonth() + 1,
                    d.getUTCDate(),
                    d.getUTCHours(),
                    d.getUTCMinutes()
                  ], doc);
                }
            """.trimIndent()
        }

    companion object {
        internal val metricFields: List<String> = listOf(
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

        internal val reduceSource: String = """
            function (keys, values, rereduce) {
              var o = {
                "interval": {},
                "reading_date": {},
                "cpu_mhz": {},
                "memory_mib": {},
                "storage_gib": {},
                "disk_io_kilobytes_per_sec": {},
                "lan_io_kilobits_per_sec": {},
                "wan_io_kilobits_per_sec": {},
                "consumption_wac": {},
                "created_at": {}
              };

              if (!rereduce) {
                var length = values.length;
                for (var k in o) {
                  var attrValue = values.map(function (v) { return v[k]; });
                  o[k].sum = sum(attrValue);
                  o[k].avg = o[k].sum / length;
                  o[k].min = Math.min.apply(null, attrValue);
                  o[k].max = Math.max.apply(null, attrValue);
                }
                return [o, length];
              }

              var count = sum(values.map(function (v) { return v[1]; }));
              for (var k in o) {
                o[k].sum = sum(values.map(function (v) { return v[0][k].sum; }));
                o[k].avg = o[k].sum / count;
                o[k].min = Math.min.apply(null, values.map(function (v) { return v[0][k].min; }));
                o[k].max = Math.max.apply(null, values.map(function (v) { return v[0][k].max; }));
              }
              return [o, count];
            }
        """.trimIndent()
    }
}
