package borg.trikeshed.couch.relaxfactory

import borg.trikeshed.couch.relaxfactory.*
import borg.trikeshed.couch.relaxfactory.CouchServiceCompiler.compile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RelaxFactoryParityRedTest {
    @Test
    fun compilesRelaxFactoryStyleServiceIntoDesignDocumentAndViewManifest() {
        val manifest: CouchViewManifest = compile<VehicleViewService>(namespace = "acme")

        assertEquals("acmevehicle", manifest.databaseName)
        assertEquals("_design/${VehicleViewService::class.qualifiedName}", manifest.designDocument.id)
        assertEquals("javascript", manifest.designDocument.language)
        assertEquals(
            "function(doc){emit(doc.brand, doc);}",
            manifest.designDocument.views.getValue("getItemsWithBrand").map,
        )
        assertEquals(
            "_count",
            manifest.designDocument.views.getValue("countMatchingBrand").reduce,
        )
    }

    @Test
    fun mapsParameterAnnotationsIntoRelaxFactoryCompatibleQueryTemplate() {
        val manifest = compile<VehicleViewService>(namespace = "acme")
        val view = manifest.views.getValue("pagedPrefixSearch")

        assertEquals(
            "_design/${VehicleViewService::class.qualifiedName}/_view/pagedPrefixSearch?descending=false&endkey=%3\$s&limit=%4\$s&skip=%5\$s&startkey=%2\$s&key=%1\$s",
            view.template,
        )
    }

    @Test
    fun methodLevelAnnotationsOverrideParametersAndEncodeGroupLevelCorrectly() {
        val manifest = compile<VehicleViewService>(namespace = "acme")
        val view = manifest.views.getValue("brandCountByPrefix")

        assertTrue(view.template.contains("group=true"))
        assertTrue(view.template.contains("group_level=2"))
        assertFalse(view.template.contains("group=2"))
    }

    @Test
    fun invokesViewsUsingCouchDb11CounterpartPathsAndJsonEncodedArguments() {
        val invocation:
            CouchViewInvocation = compile<VehicleViewService>(namespace = "acme")
            .views
            .getValue("matchingTuple")
            .invoke(VehicleDoc(model = "Golf", brand = "VW"))

        assertEquals(
            "/acmevehicle/_design/${VehicleViewService::class.qualifiedName}/_view/matchingTuple?key=%7B%22model%22%3A%22Golf%22%2C%22brand%22%3A%22VW%22%7D",
            invocation.path,
        )
    }

    @Test
    fun decodesReduceListAndMapReturnShapesWithRelaxFactoryParity() {
        val manifest = compile<VehicleViewService>(namespace = "acme")

        assertEquals(CouchViewInvocation.ReturnShape.ListValue, manifest.views.getValue("getItemsWithBrand").returnShape)
        assertEquals(CouchViewInvocation.ReturnShape.MapKeyValue, manifest.views.getValue("brandToModel").returnShape)
        assertEquals(CouchViewInvocation.ReturnShape.ScalarValue, manifest.views.getValue("countMatchingBrand").returnShape)
    }

    private interface VehicleViewService : CouchService<VehicleDoc> {
        @View(map = "function(doc){emit(doc.brand, doc);}")
        fun getItemsWithBrand(@Key brand: String): List<VehicleDoc>

        @View(map = "function(doc){emit(doc.model.slice(0,4), doc);}")
        @Descending(false)
        fun pagedPrefixSearch(
            @Key key: String,
            @StartKey startKey: String,
            @EndKey endKey: String,
            @Limit limit: Int,
            @Skip skip: Int,
        ): List<VehicleDoc>

        @View(map = "function(doc){emit({model:doc.model,brand:doc.brand}, doc);}")
        fun matchingTuple(@Key tuple: VehicleDoc): List<VehicleDoc>

        @View(map = "function(doc){emit(doc.brand, doc.model);}")
        fun brandToModel(): Map<String, String>

        @View(map = "function(doc){emit(doc.brand, 1);}", reduce = "_count")
        @Group(true)
        @GroupLevel(2)
        fun brandCountByPrefix(
            @StartKey startKey: String,
            @EndKey endKey: String,
            @StartKeyDocId startKeyDocId: String,
            @EndKeyDocId endKeyDocId: String,
        ): Map<String, Int>

        @View(map = "function(doc){emit(doc.brand, 1);}", reduce = "_count")
        fun countMatchingBrand(@Key brand: String): Int

        @View(map = "function(doc){emit(doc.model, doc);}")
        fun anyMatching(@Keys vararg keys: String): List<VehicleDoc>
    }

    private data class VehicleDoc(
        val model: String,
        val brand: String,
    )
}
