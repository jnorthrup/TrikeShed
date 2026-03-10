package borg.trikeshed.cursor

import borg.trikeshed.lib.Series
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class BlackboardOverlayTest {

    @Test
    fun testCellOverlayCreation() {
        val overlay = CellOverlay(
            value = 42,
            role = OverlayRole.OBSERVATION,
            evidence = Evidence(confidence = 0.95)
        )

        assertEquals(42, overlay.value)
        assertEquals(OverlayRole.OBSERVATION, overlay.role)
        assertEquals(0.95, overlay.evidence?.confidence)
    }

    @Test
    fun testCellOverlayMap() {
        val overlay = CellOverlay(value = "42", role = OverlayRole.OBSERVATION)
        val mapped = overlay.map { it.toInt() }

        assertEquals(42, mapped.value)
        assertEquals(OverlayRole.OBSERVATION, mapped.role)
    }

    @Test
    fun testCellOverlayDerive() {
        val provenance = Provenance(source = "test", timestamp = 1000L)
        val overlay = CellOverlay(
            value = 100,
            role = OverlayRole.OBSERVATION,
            provenance = provenance
        )

        val derived = overlay.derive(
            newRole = OverlayRole.DERIVED,
            transformation = "doubled"
        )

        assertEquals(OverlayRole.DERIVED, derived.role)
        assertEquals(2, derived.provenance?.transformations?.size)
        assertEquals("doubled", derived.provenance?.transformations?.last())
    }

    @Test
    fun testCellOverlayWithConfidence() {
        val overlay = CellOverlay(value = "test", role = OverlayRole.HYPOTHESIS)
        val updated = overlay.withConfidence(0.85)

        assertEquals(0.85, updated.evidence?.confidence)
    }

    @Test
    fun testCellOverlayWithDependency() {
        val overlay = CellOverlay(value = "test", role = OverlayRole.DERIVED)
        val dep = DependencyHandle.CellRef(0, 1)
        val updated = overlay.withDependency(dep)

        assertEquals(1, updated.dependencies.size)
        assertEquals(dep, updated.dependencies[0])
    }

    @Test
    fun testProvenanceWithTransformation() {
        val provenance = Provenance(source = "dataset.csv", timestamp = 1000L)
        val updated = provenance.withTransformation("normalized")

        assertEquals(1, updated.transformations.size)
        assertEquals("normalized", updated.transformations[0])
    }

    @Test
    fun testProvenanceDerive() {
        val provenance = Provenance(
            source = "raw_data",
            timestamp = 1000L,
            transformations = listOf("cleaned")
        )

        val derived = provenance.derive("aggregated")

        assertEquals(2, derived.transformations.size)
        assertEquals("aggregated", derived.transformations[1])
    }

    @Test
    fun testEvidenceValidation() {
        // Valid evidence
        val valid = Evidence(confidence = 0.5, errorMargin = 0.1, supportCount = 100)
        assertEquals(0.5, valid.confidence)
        assertEquals(0.1, valid.errorMargin)
        assertEquals(100, valid.supportCount)

        // Test combine
        val other = Evidence(confidence = 0.7, supportCount = 50)
        val combined = valid.combine(other)
        assertEquals(0.6, combined.confidence)
        assertEquals(150, combined.supportCount)
    }

    @Test
    fun testEvidenceConfidenceBounds() {
        // Should throw for out-of-bounds confidence
        try {
            Evidence(confidence = 1.5)
            assertFalse(true, "Should have thrown IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("confidence") ?: true)
        }

        try {
            Evidence(confidence = -0.1)
            assertFalse(true, "Should have thrown IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("confidence") ?: true)
        }
    }

    @Test
    fun testDependencyHandles() {
        val cellRef = DependencyHandle.CellRef(5, 3)
        assertEquals(5, cellRef.row)
        assertEquals(3, cellRef.column)

        val colRef = DependencyHandle.ColumnRef(2)
        assertEquals(2, colRef.column)

        val external = DependencyHandle.ExternalCellRef("cursor1", 10, 4)
        assertEquals("cursor1", external.cursorId)
        assertEquals(10, external.row)
        assertEquals(4, external.column)

        val resource = DependencyHandle.ExternalResource("file:///data.csv", selector = "columnA")
        assertEquals("file:///data.csv", resource.uri)
        assertEquals("columnA", resource.selector)

        val composite = DependencyHandle.Composite(listOf(cellRef, colRef))
        assertEquals(2, composite.handles.size)
    }

    @Test
    fun testColumnOverlay() {
        val overlay = ColumnOverlay(
            name = "price",
            defaultRole = OverlayRole.OBSERVATION,
            description = "Stock price",
            constraints = listOf("must be positive")
        )

        assertEquals("price", overlay.name)
        assertEquals(OverlayRole.OBSERVATION, overlay.defaultRole)
        assertEquals("Stock price", overlay.description)
        assertEquals(1, overlay.constraints.size)

        val cellOverlay = overlay.toCellOverlay(100.5, row = 0)
        assertEquals(100.5, cellOverlay.value)
        assertEquals(OverlayRole.OBSERVATION, cellOverlay.role)
    }

    @Test
    fun testColumnOverlayWithConstraint() {
        val overlay = ColumnOverlay(name = "volume", defaultRole = OverlayRole.OBSERVATION)
        val updated = overlay.withConstraint("must be non-negative")

        assertEquals(1, updated.constraints.size)
        assertEquals("must be non-negative", updated.constraints[0])
    }

    @Test
    fun testColumnOverlayWithDescription() {
        val overlay = ColumnOverlay(name = "timestamp", defaultRole = OverlayRole.OBSERVATION)
        val updated = overlay.withDescription("Unix timestamp in milliseconds")

        assertEquals("Unix timestamp in milliseconds", updated.description)
    }

    @Test
    fun testBlackboardContext() {
        val colOverlay = ColumnOverlay(name = "close", defaultRole = OverlayRole.OBSERVATION)
        val context = BlackboardContext(
            id = "test-context",
            columnOverlays = mapOf(0 to colOverlay),
            tags = mapOf("source" to "market_data")
        )

        assertEquals("test-context", context.id)
        assertNotNull(context.getColumnOverlay(0))
        assertNull(context.getColumnOverlay(1))
        assertEquals("market_data", context.tags["source"])
    }

    @Test
    fun testBlackboardContextEffectiveRole() {
        val colOverlay = ColumnOverlay(name = "price", defaultRole = OverlayRole.DERIVED)
        val context = BlackboardContext(
            id = "test",
            columnOverlays = mapOf(0 to colOverlay)
        )

        // Column default should apply when cell role is null
        val effectiveRole = context.getEffectiveRole(0, null)
        assertEquals(OverlayRole.DERIVED, effectiveRole)

        // Cell role should apply when provided
        val cellRole = context.getEffectiveRole(0, OverlayRole.OBSERVATION)
        assertEquals(OverlayRole.OBSERVATION, cellRole)
    }

    @Test
    fun testBlackboardContextEffectiveEvidence() {
        val colEvidence = Evidence(confidence = 0.9)
        val colOverlay = ColumnOverlay(
            name = "signal",
            defaultRole = OverlayRole.HYPOTHESIS,
            evidence = colEvidence
        )
        val context = BlackboardContext(
            id = "test",
            columnOverlays = mapOf(0 to colOverlay)
        )

        // Cell evidence overrides column evidence
        val cellEvidence = Evidence(confidence = 0.8)
        val effective = context.getEffectiveEvidence(0, cellEvidence)
        assertEquals(0.8, effective?.confidence)

        // Column evidence used when cell evidence is null
        val effectiveCol = context.getEffectiveEvidence(0, null)
        assertEquals(0.9, effectiveCol?.confidence)
    }

    @Test
    fun testBlackboardContextModification() {
        val context = BlackboardContext(id = "base")
        val colOverlay = ColumnOverlay(name = "test", defaultRole = OverlayRole.OBSERVATION)

        val withColumn = context.withColumnOverlay(0, colOverlay)
        assertNotNull(withColumn.getColumnOverlay(0))

        val withTag = withColumn.withTag("version", "1.0")
        assertEquals("1.0", withTag.tags["version"])
    }

    @Test
    fun testProvenanceBuilder() {
        val provenance = provenance(
            source = "test_source",
            timestamp = 1000L
        ) {
            transform("step1")
            transform("step2")
            creator("tester")
        }

        assertEquals("test_source", provenance.source)
        assertEquals(2, provenance.transformations.size)
        assertEquals("tester", provenance.creator)
    }

    @Test
    fun testEvidenceHelper() {
        val ev = evidence(
            confidence = 0.75,
            errorMargin = 0.05,
            supportCount = 200,
            notes = listOf("test note")
        )

        assertEquals(0.75, ev.confidence)
        assertEquals(0.05, ev.errorMargin)
        assertEquals(200, ev.supportCount)
        assertEquals(1, ev.notes.size)
    }

    @Test
    fun testCellOverlayHelper() {
        val overlay = cellOverlay(
            value = 42,
            role = OverlayRole.GROUND_TRUTH,
            evidence = evidence(confidence = 1.0)
        )

        assertEquals(42, overlay.value)
        assertEquals(OverlayRole.GROUND_TRUTH, overlay.role)
        assertEquals(1.0, overlay.evidence?.confidence)
    }

    @Test
    fun testColumnOverlayHelper() {
        val overlay = columnOverlay(
            name = "temperature",
            defaultRole = OverlayRole.OBSERVATION,
            description = "Temperature in Celsius",
            constraints = listOf("range: -50 to 50")
        )

        assertEquals("temperature", overlay.name)
        assertEquals("Temperature in Celsius", overlay.description)
        assertEquals(1, overlay.constraints.size)
    }

    @Test
    fun testBlackboardContextHelper() {
        val colOverlay = columnOverlay(name = "test", defaultRole = OverlayRole.OBSERVATION)
        val context = blackboardContext(
            id = "test-ctx",
            columnOverlays = mapOf(0 to colOverlay),
            tags = mapOf("key" to "value")
        )

        assertEquals("test-ctx", context.id)
        assertNotNull(context.getColumnOverlay(0))
        assertEquals("value", context.tags["key"])
    }

    @Test
    fun testCombineContexts() {
        val ctx1 = BlackboardContext(
            id = "ctx1",
            columnOverlays = mapOf(0 to columnOverlay(name = "a")),
            tags = mapOf("t1" to "v1")
        )
        val ctx2 = BlackboardContext(
            id = "ctx2",
            columnOverlays = mapOf(0 to columnOverlay(name = "b")),
            tags = mapOf("t2" to "v2")
        )

        val combined = combineContexts(ctx1, ctx2, offsetA = 0)

        assertEquals("ctx1+ctx2", combined.id)
        assertEquals(2, combined.columnOverlays.size)
        assertEquals("v1", combined.tags["t1"])
        assertEquals("v2", combined.tags["t2"])
    }

    @Test
    fun testDependencyHelpers() {
        val cellRef = cellRef(1, 2)
        val colRef = columnRef(3)
        val external = externalResource("file:///test.csv", selector = "col1")
        val composite = compositeDependency(cellRef, colRef)

        assertEquals(1, cellRef.row)
        assertEquals(3, colRef.column)
        assertEquals("file:///test.csv", external.uri)
        assertEquals(2, composite.handles.size)
    }
}
