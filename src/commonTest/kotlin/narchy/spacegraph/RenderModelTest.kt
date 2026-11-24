package narchy.spacegraph

import borg.trikeshed.lib.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RenderModelTest {
    private val card = RenderNode(
        id = "a", kind = "card", label = "A", position = Vec3(1.0, 2.0, 3.0),
        emphasis = 0.5, widgets = listOf("w1"), widgetSlots = listOf("top"),
    )

    @Test fun salienceAndWidgetFieldsSerializeOnTheNode() {
        assertEquals(
            """{"nodes":[{"id":"a", "kind":"card", "label":"A", "position":[1.0, 2.0, 3.0], "emphasis":0.5, "widgets":["w1"], "widgetSlots":["top"]}], "edges":[]}""",
            RenderModel(nodes = listOf(card).toSeries()).json(),
        )
    }

    @Test fun edgeEmphasisAndDataSerialize() {
        val edge = RenderEdge(id = "e", source = "a", target = "b", kind = "link", emphasis = 0.25, data = mapOf("w" to 2))
        assertEquals(
            """{"nodes":[], "edges":[{"id":"e", "source":"a", "target":"b", "kind":"link", "emphasis":0.25, "data":{"w":2}}]}""",
            RenderModel(edges = listOf(edge).toSeries()).json(),
        )
    }

    @Test fun emphasisOutsideUnitIntervalIsRefused() {
        assertFailsWith<IllegalArgumentException> { card.copy(emphasis = 1.5) }
        assertFailsWith<IllegalArgumentException> { RenderEdge("e", "a", "b", "link", emphasis = -0.1) }
    }

    @Test fun emphasisChangeIsAChangedNodeAndMergesBack() {
        val previous = RenderModel(nodes = listOf(card).toSeries())
        val next = RenderModel(nodes = listOf(card.copy(emphasis = 0.75)).toSeries())
        val patch = diffRenderModels(previous, next)
        assertEquals(emptyList(), patch.addedNodes.view.map { it.id })
        assertEquals(listOf("a"), patch.changedNodes.view.map { it.id })
        assertEquals(next.json(), mergeRenderModelPatch(previous, patch).json())
    }

    @Test fun widgetSlotChangeIsAChangedNode() {
        val previous = RenderModel(nodes = listOf(card).toSeries())
        val next = RenderModel(nodes = listOf(card.copy(widgetSlots = listOf("bottom"))).toSeries())
        assertEquals(listOf("a"), diffRenderModels(previous, next).changedNodes.view.map { it.id })
    }
}
