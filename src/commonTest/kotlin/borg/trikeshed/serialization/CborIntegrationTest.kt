package borg.trikeshed.serialization

import borg.trikeshed.parse.confix.confixDoc
import borg.trikeshed.parse.confix.Syntax
import kotlin.test.Test
import kotlin.test.assertEquals

class CborIntegrationTest {
    @Test
    fun serializationWorksEndToEndWithConfixDAG() {
        val json = """{"a": 1, "b": "test"}"""
        val doc = confixDoc(json)
        
        @kotlinx.serialization.Serializable
        data class DagNode(val id: String, val dependencies: List<String>)
        
        val node = DagNode("node-1", listOf("dep-a", "dep-b"))
        val bytes = PortableCbor.encodeToByteArray(DagNode.serializer(), node)
        val decoded = PortableCbor.decodeFromByteArray(DagNode.serializer(), bytes)
        
        assertEquals(node, decoded)
    }
}
