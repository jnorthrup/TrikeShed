package borg.trikeshed.skinnerbox

import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.*
import kotlin.test.*

class TwinStoreTest {
    private val grant = TwinGrant("verified-peer", TwinSource.entries.toSet(), s_[TwinScope("*", "")])
    private fun observation(value: Int, source: TwinSource = TwinSource.MEASURED, at: Long = value.toLong(),
        key: TwinKey = TwinKey("model", "test")) =
        TwinObservation.create(key, source, "fixture", at, mapOf("value" to value))

    @Test fun canonicalNumbersNeverRoundThroughDouble() {
        val value = TwinJson.parse("{\"large\":9007199254740993,\"decimal\":1.2300e2}".encodeToByteArray())
        assertEquals("{\"decimal\":123,\"large\":9007199254740993}", TwinJson.encode(value).decodeToString())
        assertEquals("{\"small\":1e-20}", TwinJson.encode(TwinJson.parse("{\"small\":0.00000000000000000001}".encodeToByteArray())).decodeToString())
        assertEquals(ULong.MAX_VALUE, TwinJson.parse("18446744073709551615".encodeToByteArray()))
        assertEquals("1e999999", TwinJson.encode(TwinJson.parse("1e999999".encodeToByteArray())).decodeToString())
        assertFails { TwinNumber.of("1e1000001") }
    }

    @Test fun hostileJsonIsRefusedBeforeObservationAdmission() {
        for (text in listOf("{\"x\":1,\"x\":2}", "{\"x\":1,\"\\u0078\":2}", "[1,]", "{}{}", "{\"x\":01}", "{\"x\":\"\\ud800\"}")) {
            assertFails { TwinJson.parse(text.encodeToByteArray()) }
        }
        assertFails { TwinJson.parse(("[".repeat(18) + "0" + "]".repeat(18)).encodeToByteArray()) }
        assertFails { TwinJson.parse(("[" + List(2049) { "0" }.joinToString(",") + "]").encodeToByteArray()) }
        assertFails { TwinJson.parse(byteArrayOf(0xc0.toByte(), 0xaf.toByte())) }
    }

    @Test fun observationsDoNotExposeTheirIdentityBacking() {
        val fields = mutableMapOf<String, Any?>("nested" to mutableMapOf("value" to 1))
        val item = TwinObservation.create(TwinKey("user", "alice"), TwinSource.CURATED, "fixture", 1, fields)
        val original = item.bytes()
        (fields["nested"] as MutableMap<String, Int>)["value"] = 2
        item.bytes().fill(0)
        (item.fields()["nested"] as MutableMap<String, Any?>)["value"] = 3
        assertContentEquals(original, item.bytes())
        assertEquals(ContentId.of(original), item.id)
        assertEquals(item.id, TwinObservation.parse(original).id)
    }

    @Test fun foldAndSavepointConvergeAcrossAllSixArrivalOrders() {
        val planned = observation(1, TwinSource.PLANNED, 999)
        val measured = observation(2, TwinSource.MEASURED, 2)
        val latest = observation(3, TwinSource.MEASURED, 3)
        val orders = listOf(listOf(planned, measured, latest), listOf(planned, latest, measured),
            listOf(latest, planned, measured), listOf(latest, measured, planned),
            listOf(measured, latest, planned), listOf(measured, planned, latest))
        val stores = orders.map { order -> TwinStore().also { s -> order.forEach { s.insert(it, grant) } } }
        for (store in stores) {
            assertEquals(latest.id, store.get(latest.key)?.id)
            assertEquals(stores[0].savepoint(), store.savepoint())
            assertEquals(3, store.size)
            assertEquals(0, store.merge(s_[planned, latest], grant))
        }
    }

    @Test fun hardwareRevisionKeepsFirstMeasurementAndHashBreaksTimeTies() {
        val key = TwinKey("hardware", "revision/a40/r1")
        val first = observation(2, at = 2, key = key)
        val later = observation(3, at = 3, key = key)
        val sameTime = observation(4, at = 2, key = key)
        val store = TwinStore()
        store.merge(s_[later, first, sameTime], grant)
        assertEquals(maxOf(first.id.value, sameTime.id.value), store.get(key)?.id?.value)
    }

    @Test fun aRejectedUnionDoesNotPublishAnyOfItsPrefix() {
        val store = TwinStore(maxObservations = 2)
        val one = observation(1)
        store.insert(one, grant)
        val before = store.savepoint()
        assertFails { store.merge(s_[observation(2), observation(3)], grant) }
        assertEquals(before, store.savepoint())
        assertEquals(1, store.size)
        val bytes = TwinStore(maxBytes = one.bytes().size.toLong())
        bytes.insert(one, grant)
        assertFails { bytes.insert(observation(2), grant) }
        assertEquals(1, bytes.size)
    }

    @Test fun measuredSourceAndNamespaceNeedExplicitAuthority() {
        val limited = TwinGrant("verified-user", setOf(TwinSource.PLANNED), s_[TwinScope("user", "alice/")])
        val store = TwinStore()
        val permitted = observation(1, TwinSource.PLANNED, key = TwinKey("user", "alice/device"))
        assertFails { store.merge(s_[permitted, observation(2)], limited) }
        assertEquals(0, store.size)
        assertEquals(1, store.insert(permitted, limited))
        assertFails { store.insert(observation(3, TwinSource.PLANNED, key = TwinKey("user", "alice-other/device")), limited) }
    }

    @Test fun residualPagesReconstructAllHistoryAndDetectChangedSavepoint() {
        val store = TwinStore()
        store.merge((1..7).map { observation(it) }.toSeries(), grant)
        val restored = TwinStore()
        var after: ContentId? = null
        do {
            val page = store.residual(restored.observations() α { it.id }, limit = 2, after = after)
            restored.merge(page.observations α { TwinObservation.parse(it.bytes()) }, grant)
            after = page.next
        } while (after != null)
        assertEquals(store.savepoint(), restored.savepoint())
        assertEquals(7, restored.size)
        val old = store.savepoint()
        store.insert(observation(8), grant)
        assertFails { store.residual(emptySeriesOf(), expectedSavepoint = old) }
    }

    @Test fun referencesRepairWithoutDiscardingEarlyObservations() {
        val store = TwinStore()
        val country = TwinObservation.create(TwinKey("region", "country/US"), TwinSource.CURATED, "fixture", 1, mapOf("continent" to "NA"))
        store.insert(country, grant)
        assertEquals(1, store.referenceIssues().size)
        store.insert(TwinObservation.create(TwinKey("region", "continent/NA"), TwinSource.CURATED, "fixture", 1, emptyMap()), grant)
        assertEquals(0, store.referenceIssues().size)
        assertEquals(country.id, store.get(country.key)?.id)
    }

    @Test fun referencePagesBoundOwnersAndDiagnosticsWithoutLosingEdges() {
        val store = TwinStore()
        store.insert(TwinObservation.create(TwinKey("user", "many"), TwinSource.CURATED, "fixture", 1,
            mapOf("references" to List(70) { mapOf("kind" to "user", "key" to "missing$it") })), grant)
        var cursor: TwinReferenceCursor? = null
        val found = mutableListOf<TwinReferenceIssue>()
        val snapshot = store.savepoint()
        do {
            val page = store.referencePage(limit = 7, maxOwners = 1, after = cursor, expectedSavepoint = snapshot)
            assertTrue(page.issues.size <= 7)
            found.addAll(page.issues.view)
            cursor = page.next
        } while (cursor != null)
        assertEquals(70, found.size)
        assertEquals(70, found.map { it.target }.toSet().size)
        val first = store.referencePage(limit = 1)
        assertFails { store.referencePage(after = first.next) }
        store.insert(TwinObservation.create(TwinKey("user", "missing0"), TwinSource.CURATED, "fixture", 1, emptyMap()), grant)
        assertFails { store.referencePage(after = first.next, expectedSavepoint = snapshot) }

        val clean = TwinStore()
        clean.merge(List(70) { TwinObservation.create(TwinKey("user", "u${it.toString().padStart(2, '0')}"), TwinSource.CURATED, "fixture", 1, emptyMap()) }.toSeries(), grant)
        val emptyPage = clean.referencePage(maxOwners = 64)
        assertEquals(0, emptyPage.issues.size)
        assertNotNull(emptyPage.next)
        assertNull(clean.referencePage(after = emptyPage.next, expectedSavepoint = emptyPage.savepoint).next)
    }

    @Test fun legacyConversionRetainsItsOldIdentityAsProvenance() {
        val line = "{\"at_ms\":1,\"fields\":{\"count\":9007199254740993},\"key\":\"alice\",\"kind\":\"user\",\"source\":\"curated\",\"twin\":\"loom.twin/v1\",\"via\":\"git\"}".encodeToByteArray()
        val converted = TwinObservation.importLegacy(line)
        assertEquals(ContentId.of(line), converted.legacyId)
        assertNotEquals(ContentId.of(line), converted.id)
        assertEquals(9007199254740993L, converted.fields()["count"])
        assertEquals(converted.id, TwinObservation.parse(converted.bytes()).id)
        assertFails { TwinObservation.parse(line) }
    }
}
