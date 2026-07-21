package borg.trikeshed.lcnc.reactor

import borg.trikeshed.context.AsyncContextElement
import borg.trikeshed.context.ElementState
import borg.trikeshed.context.nuid.*
import borg.trikeshed.lcnc.ccek.IngestStateElement
import borg.trikeshed.lcnc.isam.LcncEntity
import borg.trikeshed.lcnc.isam.LcncPage
import borg.trikeshed.lcnc.isam.LcncBlock
import borg.trikeshed.lcnc.isam.LcncDatabase
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.emptySeriesOf
import borg.trikeshed.lib.j
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.currentCoroutineContext
import kotlin.text.Regex

/**
 * Configuration for ingestion pipeline behavior under load.
 * Uses nanosecond durations for commonMain compatibility (no kotlin.time required).
 */
data class IngestPipelineConfig(
    val maxConcurrentParses: Int = (Runtime.getRuntime().availableProcessors() * 2).coerceAtLeast(4),
    val fanoutBufferSize: Int = 1024,
    val publishBatchSize: Int = 64,
    val parseTimeoutNs: Long = 30_000_000_000L,
    val publishTimeoutNs: Long = 5_000_000_000L,
    val maxPendingEntities: Int = 10000,
    val enableParallelParsing: Boolean = true,
    val minParallelChunkSize: Int = 1000,
)

data class IngestMetrics(
    val entitiesParsed: Long = 0L,
    val entitiesPublished: Long = 0L,
    val parseErrors: Long = 0L,
    val publishErrors: Long = 0L,
    val totalParseTimeNs: Long = 0L,
    val totalPublishTimeNs: Long = 0L,
    val backpressureEvents: Long = 0L,
) {
    fun recordParse(count: Int, durationNs: Long) = copy(
        entitiesParsed = entitiesParsed + count,
        totalParseTimeNs = totalParseTimeNs + durationNs,
    )
    fun recordPublish(count: Int, durationNs: Long) = copy(
        entitiesPublished = entitiesPublished + count,
        totalPublishTimeNs = totalPublishTimeNs + durationNs,
    )
    fun recordParseError() = copy(parseErrors = parseErrors + 1)
    fun recordPublishError() = copy(publishErrors = publishErrors + 1)
    fun recordBackpressure() = copy(backpressureEvents = backpressureEvents + 1)
}

sealed class PipelineStage<T> {
    data class Parsed<T>(val items: List<T>) : PipelineStage<T>()
    data class Transformed<T>(val items: List<T>) : PipelineStage<T>()
    data class Validated<T>(val items: List<T>) : PipelineStage<T>()
    data class Published<T>(val count: Int) : PipelineStage<T>()
    data class Failed(val error: Throwable, val stage: String) : PipelineStage<Nothing>()
}

class LcncIngestPipeline(
    parentJob: Job? = null,
    val ingestId: String,
    override val supportedFormats: Set<IngestFormat> = IngestFormat.entries.toSet(),
    val config: IngestPipelineConfig = IngestPipelineConfig(),
) : AsyncContextElement(ElementState.CREATED, parentJob), IngestCodec {

    companion object Key : kotlin.coroutines.CoroutineContext.Key<LcncIngestPipeline>
    override val key = Key

    
    private val _metrics = MutableSharedFlow<IngestMetrics>(replay = 1)
    val metrics: SharedFlow<IngestMetrics> = _metrics.asSharedFlow()
    
    private var currentMetrics = IngestMetrics()
    

    override suspend fun decode(source: IngestSource, format: IngestFormat): Series<LcncEntity> {
        val stateElement = currentCoroutineContext()[IngestStateElement]
        val mockNuid = nuid(Capability.Custom("lcnc", "ingest"), Nonce.RandomBytes(), Subnet.core)

        stateElement?.fanout?.send(ReactorAction.opened(mockNuid))
        
        val startTime = System.nanoTime()
        
        try {
            val entities = parse(source, format)
            val parseDuration = System.nanoTime() - startTime
            currentMetrics = currentMetrics.recordParse(entities.a, parseDuration)
            _metrics.tryEmit(currentMetrics)
            
            stateElement?.fanout?.send(ReactorAction.activated(mockNuid))

            var publishedCount = 0
            for (i in 0 until entities.a) {
                val entity = entities.b(i)
                stateElement?.fanout?.send(ReactorAction.publishEntity(mockNuid, entity))
                publishedCount++
            }
            
            val publishDuration = System.nanoTime() - startTime - parseDuration
            currentMetrics = currentMetrics.recordPublish(publishedCount, publishDuration)
            _metrics.tryEmit(currentMetrics)
            
            stateElement?.fanout?.send(ReactorAction.draining(mockNuid))
            stateElement?.fanout?.send(ReactorAction.closed(mockNuid))

            return entities
            
        } catch (e: Throwable) {
            currentMetrics = currentMetrics.recordParseError()
            _metrics.tryEmit(currentMetrics)
            throw e
        }
    }

    private suspend fun parse(source: IngestSource, format: IngestFormat): Series<LcncEntity> {
        val text = when (source) {
            is IngestSource.Paste -> source.content
            is IngestSource.FileStream -> source.data.decodeToString()
            is IngestSource.Link -> source.uri
        }
        
        return when (format) {
            IngestFormat.MARKDOWN -> parseMarkdown(text)
            IngestFormat.CSV, IngestFormat.TSV -> parseCsvOrTsv(text, format)
            IngestFormat.JSON -> parseJson(text)
            IngestFormat.HTML -> parseHtml(text)
            IngestFormat.LCNC_NATIVE -> parseLcncNative(text)
            else -> emptySeriesOf()
        }
    }

    private fun parseMarkdown(text: String): Series<LcncEntity> {
        val lines = text.lines()
        val headerRegex = Regex("^(#+)\\s+(.+)$")
        
        return lines.size j { i ->
            val line = lines[i]
            val match = headerRegex.matchEntire(line.trim())
            if (match != null) {
                val level = match.groupValues[1].length
                val title = match.groupValues[2].trim()
                val id = title.lowercase().replace(Regex("[^a-z0-9]+"), "-")
                
                LcncBlock(
                    id = id,
                    type = "heading_${'$'}level",
                    parentId = "root",
                    content = title
                )
            } else {
                LcncBlock(
                    id = "p-${'$'}i",
                    type = "paragraph",
                    parentId = "root",
                    content = line
                )
            }
        }
    }

    private fun parseCsvOrTsv(text: String, format: IngestFormat): Series<LcncEntity> {
        val delimiter = if (format == IngestFormat.CSV) "," else "\t"
        val lines = text.lines().filter { it.isNotBlank() }
        if (lines.isEmpty()) return emptySeriesOf()
        
        val headers = lines.first().split(delimiter).map { it.trim() }
        val inferredColumns = headers.joinToString(", ")
        
        val databaseId = "db-${'$'}{text.hashCode()}"

        val pages: Series<LcncPage> = (lines.size - 1) j { i: Int ->
            val values = lines[i + 1].split(delimiter).map { it.trim() }
            val props = headers.zip(values).toMap()
            val id = props["id"] ?: props["title"] ?: "row-${'$'}i"
            LcncPage(
                id = id,
                title = props["title"] ?: id,
                parentId = databaseId,
                contentBlocks = emptySeriesOf<LcncBlock>()
            )
        }
        
        val db = borg.trikeshed.lcnc.isam.LcncDatabase(
            id = databaseId,
            title = "Imported Database (Columns: ${'$'}inferredColumns)",
            parentId = null,
            pages = pages
        )

        return 1 j { db }
    }

    private fun parseJson(text: String): Series<LcncEntity> {
        return 1 j { LcncPage(
            id = "json-import",
            title = "JSON Import",
            parentId = null,
            contentBlocks = emptySeriesOf<LcncBlock>()
        ) }
    }

    private fun parseHtml(text: String): Series<LcncEntity> {
        return 1 j { LcncPage(
            id = "html-import",
            title = "HTML Import",
            parentId = null,
            contentBlocks = emptySeriesOf<LcncBlock>()
        ) }
    }

    private fun parseLcncNative(text: String): Series<LcncEntity> {
        return 1 j { LcncPage(
            id = "lcnc-native",
            title = "LCNC Native Import",
            parentId = null,
            contentBlocks = emptySeriesOf<LcncBlock>()
        ) }
    }
    
    suspend fun getMetrics(): IngestMetrics = currentMetrics
    
    suspend fun resetMetrics() {
        currentMetrics = IngestMetrics()
        _metrics.tryEmit(currentMetrics)
    }
}

class IngestPipelineConfigBuilder {
    private var maxConcurrentParses: Int = (Runtime.getRuntime().availableProcessors() * 2).coerceAtLeast(4)
    private var fanoutBufferSize: Int = 1024
    private var publishBatchSize: Int = 64
    private var parseTimeoutNs: Long = 30_000_000_000L
    private var publishTimeoutNs: Long = 5_000_000_000L
    private var maxPendingEntities: Int = 10000
    private var enableParallelParsing: Boolean = true
    private var minParallelChunkSize: Int = 1000
    
    fun maxConcurrentParses(n: Int): IngestPipelineConfigBuilder { maxConcurrentParses = n; return this }
    fun fanoutBufferSize(n: Int): IngestPipelineConfigBuilder { fanoutBufferSize = n; return this }
    fun publishBatchSize(n: Int): IngestPipelineConfigBuilder { publishBatchSize = n; return this }
    fun parseTimeoutNs(ns: Long): IngestPipelineConfigBuilder { parseTimeoutNs = ns; return this }
    fun publishTimeoutNs(ns: Long): IngestPipelineConfigBuilder { publishTimeoutNs = ns; return this }
    fun maxPendingEntities(n: Int): IngestPipelineConfigBuilder { maxPendingEntities = n; return this }
    fun enableParallelParsing(b: Boolean): IngestPipelineConfigBuilder { enableParallelParsing = b; return this }
    fun minParallelChunkSize(n: Int): IngestPipelineConfigBuilder { minParallelChunkSize = n; return this }
    
    fun forHighThroughput(): IngestPipelineConfigBuilder = apply {
        maxConcurrentParses = (Runtime.getRuntime().availableProcessors() * 4).coerceAtLeast(8)
        fanoutBufferSize = 4096
        publishBatchSize = 256
        maxPendingEntities = 50000
    }
    
    fun forLowLatency(): IngestPipelineConfigBuilder = apply {
        maxConcurrentParses = Runtime.getRuntime().availableProcessors()
        fanoutBufferSize = 256
        publishBatchSize = 16
        parseTimeoutNs = 5_000_000_000L
        publishTimeoutNs = 1_000_000_000L
        maxPendingEntities = 1000
    }
    
    fun forMemoryConstrained(): IngestPipelineConfigBuilder = apply {
        maxConcurrentParses = 2
        fanoutBufferSize = 128
        publishBatchSize = 8
        maxPendingEntities = 1000
        enableParallelParsing = false
    }
    
    fun build(): IngestPipelineConfig = IngestPipelineConfig(
        maxConcurrentParses = maxConcurrentParses,
        fanoutBufferSize = fanoutBufferSize,
        publishBatchSize = publishBatchSize,
        parseTimeoutNs = parseTimeoutNs,
        publishTimeoutNs = publishTimeoutNs,
        maxPendingEntities = maxPendingEntities,
        enableParallelParsing = enableParallelParsing,
        minParallelChunkSize = minParallelChunkSize,
    )
}
