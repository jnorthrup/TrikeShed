package borg.trikeshed.lcnc.reactor

import borg.trikeshed.lcnc.isam.LcncDatabase
import borg.trikeshed.lcnc.ccek.IngestStateElement
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LcncIngestPipelineTest {
    
    @Test
    fun testCsvIngestProducesDatabase() = runTest {
        val pipeline = LcncIngestPipeline(ingestId = "test-ingest")
        
        val csvContent = """
            id,title,value
            1,Row 1,100
            2,Row 2,200
        """.trimIndent()
        
        val state = IngestStateElement("test-ingest")
        
        val result = withContext(state) {
            pipeline.decode(IngestSource.Paste(csvContent), IngestFormat.CSV)
        }
        
        assertEquals(1, result.a)
        val db = result.b(0)
        assertTrue(db is LcncDatabase)
        
        val pages = db.pages
        assertEquals(2, pages.a)
        
        val page1 = pages.b(0)
        assertEquals("1", page1.id)
        assertEquals("Row 1", page1.title)
        assertEquals(db.id, page1.parentId)
        
        val page2 = pages.b(1)
        assertEquals("2", page2.id)
        assertEquals("Row 2", page2.title)
        assertEquals(db.id, page2.parentId)
    }
}
