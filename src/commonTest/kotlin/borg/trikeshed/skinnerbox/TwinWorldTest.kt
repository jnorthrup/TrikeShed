package borg.trikeshed.skinnerbox

import borg.trikeshed.lib.*
import kotlin.test.*

class TwinWorldTest {
    private val grant = TwinGrant("author", TwinSource.entries.toSet(), s_[TwinScope("*", "")])
    private fun item(kind: String, key: String, fields: Map<String, Any?>, at: Long = 1, source: TwinSource = TwinSource.CURATED) =
        TwinObservation.create(TwinKey(kind, key), source, "fixture", at, fields)
    private fun family(context: Long = 262_144, recurrent: Long = 0) = mapOf("trained_context" to context,
        "compute_allowance_bytes" to (256L shl 20), "memory" to mapOf("attention_layers" to 1L, "kv_heads" to 1L,
            "k_row_bytes" to 1L, "v_row_bytes" to 1L, "recurrent_bytes_per_sequence" to recurrent, "mtp_kv_layers" to 1L))
    private fun quant(name: String, bytes: Long, sha: String = name.first().code.toString(16).padStart(64, '0')) =
        item("quant", "m/$name", mapOf("sha256" to sha, "bytes" to bytes, "variant" to "MTP"))
    private fun fixtureStore(): TwinStore = TwinStore().also { it.merge(s_[
        item("model", "family/f", family()), item("model", "m", mapOf("family" to "f")),
        item("hardware", "class/gpu", mapOf("gpu_type_id" to "fixture-GPU", "label_gb" to 1L)),
        item("region", "continent/NA", emptyMap()), item("region", "country/US", mapOf("continent" to "NA")),
        item("rental", "vendor/gpu/us", mapOf("country_code" to "US", "micro_usd_per_hour" to 101L)),
        quant("large", 3L shl 30), quant("medium", 512L shl 20), quant("small", 128L shl 20),
    ], grant) }
    private fun project(store: TwinStore) = TwinWorld.project(store, "m", "gpu", "NA")
    @Suppress("UNCHECKED_CAST") private fun line(project: TwinProjection) = project.fields()["line"] as Map<String, Any?>

    @Test fun plannedCapacityLargestFittingQuantAndExactFormulaStayDerived() {
        val store = fixtureStore()
        val count = store.size
        val projection = project(store)
        assertEquals(15L * (1L shl 26), projection.hardware?.usableBytes)
        assertFalse(projection.hardware!!.measured)
        assertEquals(TwinFit(262_144, 4, 816_421_274), projection.fit)
        val line = line(projection)
        assertEquals("medium", line["quant"])
        assertEquals(134L, line["posted_micro_usd_per_hour"])
        assertEquals("half-up-once", line["price_rounding"])
        assertEquals(false, line["execution_qualified"])
        assertEquals(3L, line["volume_gb"])
        assertEquals(count, store.size)
        assertEquals(store.savepoint(), projection.savepoint)
        assertTrue((projection.fields()["provenance"] as List<*>).isNotEmpty())
    }

    @Test fun firstMeasurementPerRevisionAndMinimumAcrossRevisionsDetermineCapacity() {
        val store = fixtureStore()
        val r1 = item("hardware", "revision/gpu/r1", mapOf("free_bytes" to (900L shl 20)), source = TwinSource.MEASURED)
        store.merge(s_[r1,
            item("hardware", "revision/gpu/r1", mapOf("free_bytes" to (600L shl 20)), at = 2, source = TwinSource.MEASURED),
            item("hardware", "revision/gpu/r2", mapOf("free_bytes" to (850L shl 20)), source = TwinSource.MEASURED),
        ], grant)
        assertEquals(r1.id, store.get(r1.key)?.id)
        val hardware = project(store).hardware!!
        assertEquals(850L shl 20, hardware.usableBytes)
        assertEquals(2, hardware.measuredRevisions)
        assertTrue(hardware.measured)
    }

    @Test fun slotsFallBackToOneAndContextIsTheLargestCellWithinBudget() {
        val store = fixtureStore()
        store.insert(item("model", "family/f", family(recurrent = 32L shl 20), at = 2), grant)
        val projection = project(store)
        assertEquals(1L, projection.fit?.slots)
        assertEquals(262_144L, projection.fit?.contextTokens)
        // A shorter trained context stays in 256-token cells without being rounded above its training bound.
        store.insert(item("model", "family/f", family(context = 300, recurrent = 32L shl 20), at = 3), grant)
        assertEquals(256L, project(store).fit?.contextTokens)
    }

    @Test fun sourceQwenMtpDimensionsProduceTheBoundedFifteenGiBFit() {
        val store = fixtureStore()
        store.insert(item("hardware", "class/gpu", mapOf("gpu_type_id" to "16-GiB-card", "label_gb" to 16L), at = 2), grant)
        store.insert(item("model", "family/f", mapOf("trained_context" to 262_144L,
            "compute_allowance_bytes" to (3L shl 30), "memory" to mapOf("attention_layers" to 16L, "kv_heads" to 4L,
                "k_row_bytes" to 272L, "v_row_bytes" to 272L, "recurrent_bytes_per_sequence" to 156_893_184L,
                "mtp_kv_layers" to 1L)), at = 2), grant)
        store.insert(quant("IQ2_M", 12_124_624_416L, "ee4fc4950338cc95e804bff5d5c51f0a55ef7b196f9dd6fbaddf62183d189dda"), grant)
        val result = project(store)
        assertEquals("IQ2_M", line(result)["quant"])
        assertEquals(TwinFit(3328, 1, 16_100_700_090L), result.fit)
        assertTrue(16_110_170_042L > result.hardware!!.usableBytes)
    }

    @Test fun geographyAndRentalPriceComeFromReferencedTwins() {
        val store = fixtureStore()
        store.merge(s_[item("region", "country/FR", mapOf("continent" to "EU")),
            item("rental", "cheaper/gpu/fr", mapOf("country_code" to "FR", "micro_usd_per_hour" to 1L)),
            item("rental", "best/gpu/us", mapOf("country_code" to "US", "micro_usd_per_hour" to 100L)),
        ], grant)
        val line = line(project(store))
        assertEquals("best/gpu/us", line["rental"])
        assertEquals(133L, line["posted_micro_usd_per_hour"])
        assertEquals("US", line["country"])
    }

    @Test fun malformedFieldsMissingReferencesAndConflictingArtifactsDoNotBecomeOffers() {
        val store = fixtureStore()
        store.insert(item("model", "family/f", family() + ("trained_context" to "262144"), at = 2), grant)
        val bad = project(store).fields()
        assertNull(bad["line"])
        assertTrue((bad["problems"] as List<*>).isNotEmpty())
        assertNull(TwinWorld.project(store, "missing", "gpu", "NA").fields()["line"])
        val conflicts = fixtureStore()
        conflicts.insert(quant("duplicate", 256L shl 20, "medium".first().code.toString(16).padStart(64, '0')), grant)
        val projected = project(conflicts)
        assertEquals("small", line(projected)["quant"])
        assertTrue((projected.fields()["problems"] as List<*>).any { it.toString().contains("conflicting quant") })
    }

    @Test fun overflowingPriceAndCandidateFloodFailWithoutAuthoritativeMutation() {
        val store = fixtureStore()
        store.insert(item("rental", "vendor/gpu/us", mapOf("country_code" to "US", "micro_usd_per_hour" to Long.MAX_VALUE), at = 2), grant)
        assertNull(project(store).fields()["line"])
        val before = store.savepoint()
        store.merge(List(65) { item("hardware", "revision/gpu/r$it", mapOf("free_bytes" to 1L), source = TwinSource.MEASURED) }.toSeries(), grant)
        val after = store.savepoint()
        assertNotEquals(before, after)
        assertFails { project(store) }
        assertEquals(after, store.savepoint())
    }
}
