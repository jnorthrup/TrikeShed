package borg.trikeshed.lcnc.editor

import borg.trikeshed.lcnc.isam.LcncBlock
import borg.trikeshed.lcnc.isam.LcncDatabase
import borg.trikeshed.lcnc.isam.LcncPage
import borg.trikeshed.lcnc.ccek.IngestStateElement
import borg.trikeshed.lcnc.collections.associative.DatabaseSchema
import borg.trikeshed.lcnc.collections.associative.PropertySchema
import borg.trikeshed.lcnc.collections.associative.PropertyType
import borg.trikeshed.lcnc.reactor.ReactorAction
import borg.trikeshed.lib.j
import borg.trikeshed.lib.Series
import borg.trikeshed.couch.ConfixDocStore
import borg.trikeshed.parse.confix.confixDoc
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.fail

class JulesEditorIntegrationTest {

    @Test
    fun testBlockEditorRendersJulesSessionWithCrud() = runTest {
        fail("not implemented")
    }

    @Test
    fun testBlockEditorHandlesDragAndDropInCanvas() = runTest {
        fail("not implemented")
    }
    
    @Test
    fun testBlockStatePersistsToDatabaseRecords() = runTest {
        fail("not implemented")
    }

    @Test
    fun testDatabaseRecordsDisplayAsEditableBlocks() = runTest {
        fail("not implemented")
    }

    @Test
    fun testPropertyEditorsHandleNameEmailPhoneAsInJulesRestClient() = runTest {
        fail("not implemented")
    }

    @Test
    fun testEditorUpdatesMockConfixDagStorageAndVerifySerialization() = runTest {
        fail("not implemented")
    }
}
