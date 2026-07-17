package borg.trikeshed.job.schema

import borg.trikeshed.job.ConfixFacetPlan
import borg.trikeshed.parse.confix.ConfixDoc
import borg.trikeshed.parse.confix.confixDoc
import borg.trikeshed.parse.confix.Syntax
import borg.trikeshed.parse.confix.docAt
import borg.trikeshed.parse.confix.get
import borg.trikeshed.parse.confix.reify
import borg.trikeshed.parse.confix.cellKids
import borg.trikeshed.parse.confix.ConfixCell

object SchemaCompiler {
    fun compilePlan(schemaBytes: ByteArray): ConfixFacetPlan {
        val doc = confixDoc(schemaBytes, Syntax.JSON)
        return compilePlanFromDoc(doc, schemaBytes.decodeToString())
    }

    fun compilePlan(schemaText: String): ConfixFacetPlan {
        val doc = confixDoc(schemaText.encodeToByteArray(), Syntax.JSON)
        return compilePlanFromDoc(doc, schemaText)
    }

    private fun compilePlanFromDoc(doc: ConfixDoc, schemaText: String): ConfixFacetPlan {
        val defs = doc.docAt("${'$'}defs") ?: doc.docAt("definitions")
            ?: throw IllegalArgumentException("Schema missing \$defs or definitions")

        val commandDef = defs["command"] ?: throw IllegalArgumentException("Schema missing command definition")
        val commandOperations = extractEnum(commandDef, "operation")

        val eventDef = defs["event"] ?: throw IllegalArgumentException("Schema missing event definition")
        val eventOperations = extractEnum(eventDef, "operation")

        val commonFacets = defs["commonFacets"] ?: throw IllegalArgumentException("Schema missing commonFacets definition")
        val requiredFields = extractRequired(commonFacets)

        return ConfixFacetPlan(
            commandOperations = commandOperations,
            eventOperations = eventOperations,
            requiredFields = requiredFields,
            schemaText = schemaText
        )
    }

    private fun extractRequired(defNode: ConfixCell): Set<String> {
        val reqKids = defNode["required"]?.cellKids ?: return emptySet()
        val result = mutableSetOf<String>()
        for (i in 0 until reqKids.a) {
            val v = reqKids.b(i).reify()?.toString()
            if (v != null) result.add(v)
        }
        return result
    }

    private fun extractEnum(defNode: ConfixCell, fieldName: String): Set<String> {
        val allOfKids = defNode["allOf"]?.cellKids ?: return emptySet()
        var propertiesNode: ConfixCell? = null
        for (i in 0 until allOfKids.a) {
            val item = allOfKids.b(i)
            val p = item["properties"]
            if (p != null) {
                propertiesNode = p
                break
            }
        }
        if (propertiesNode == null) return emptySet()

        val fieldNode = propertiesNode[fieldName] ?: return emptySet()
        val enumKids = fieldNode["enum"]?.cellKids ?: return emptySet()

        val result = mutableSetOf<String>()
        for (i in 0 until enumKids.a) {
            val v = enumKids.b(i).reify()?.toString()
            if (v != null) {
                result.add(v)
            }
        }
        return result
    }
}
