package borg.trikeshed.couch.dsl

import borg.trikeshed.couch.minidsl.CouchMiniDsl
import borg.trikeshed.couch.minidsl.DesignRef
import borg.trikeshed.couch.minidsl.ViewRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InfixMiniDslRedTest {
    @Test
    fun expressesViewQueriesAsInfixCompositionInsteadOfProceduralBuilders() {
        val design: DesignRef = "acmevehicle" design "vehicle-service"
        val view: ViewRef = design view "by_brand"

        val query =
            view whereKey "vw" limit 10 descending false includeDocs true

        assertEquals("acmevehicle", query.database)
        assertEquals("vehicle-service", query.designDocument)
        assertEquals("by_brand", query.viewName)
        assertEquals("vw", query.parameters.getValue("key"))
        assertEquals("10", query.parameters.getValue("limit"))
        assertEquals("false", query.parameters.getValue("descending"))
        assertEquals("true", query.parameters.getValue("include_docs"))
    }

    @Test
    fun supportsInfixReduceGroupingWithoutBuilderState() {
        val query =
            ("acmevehicle" design "vehicle-service" view "brand_count")
                .group level 2

        assertTrue(query.parameters.containsKey("group"))
        assertEquals("true", query.parameters.getValue("group"))
        assertEquals("2", query.parameters.getValue("group_level"))
    }
}
