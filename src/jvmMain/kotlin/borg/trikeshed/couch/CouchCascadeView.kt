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

              // O(N) optimization: Calculate min, max, and sum in a single pass.
              // This avoids intermediate array allocations from values.map() and prevents
              // "RangeError: Maximum call stack size exceeded" caused by Math.max.apply() on large datasets.
              var length = values.length;
              if (!rereduce) {
                if (length > 0) {
                  var first = values[0];
                  for (var k in o) {
                    o[k].sum = first[k];
                    o[k].min = first[k];
                    o[k].max = first[k];
                  }
                  for (var i = 1; i < length; i++) {
                    var v = values[i];
                    for (var k in o) {
                      var val = v[k];
                      o[k].sum += val;
                      if (val < o[k].min) o[k].min = val;
                      if (val > o[k].max) o[k].max = val;
                    }
                  }
                }
                for (var k in o) {
                  o[k].avg = o[k].sum / length;
                }
                return [o, length];
              }

              var count = 0;
              if (length > 0) {
                count = values[0][1];
                var firstObj = values[0][0];
                for (var k in o) {
                  o[k].sum = firstObj[k].sum;
                  o[k].min = firstObj[k].min;
                  o[k].max = firstObj[k].max;
                }
                for (var i = 1; i < length; i++) {
                  var v = values[i];
                  count += v[1];
                  var vObj = v[0];
                  for (var k in o) {
                    o[k].sum += vObj[k].sum;
                    if (vObj[k].min < o[k].min) o[k].min = vObj[k].min;
                    if (vObj[k].max > o[k].max) o[k].max = vObj[k].max;
                  }
                }
              }
              for (var k in o) {
                o[k].avg = o[k].sum / count;
              }
              return [o, count];
            }
        """.trimIndent()
    }
}
