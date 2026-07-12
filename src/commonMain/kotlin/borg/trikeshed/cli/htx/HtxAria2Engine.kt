package borg.trikeshed.cli.htx

import borg.trikeshed.htx.*
import borg.trikeshed.lib.ByteSeries
import borg.trikeshed.userspace.nio.file.spi.FileOperations
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.sync.withLock

class HtxAria2Engine(
    private val options: Aria2Options,
    private val clientReactor: HtxClientReactorElement,
    private val fileOps: FileOperations
) {

    suspend fun execute() {
        if (options.uris.isEmpty()) {
            throw IllegalArgumentException("No URIs provided")
        }

        val primaryUri = options.uris.first()

        val headRequest = parseHtxRequest(primaryUri, method = HtxMethod.HEAD)
        val headResponse = clientReactor.request(headRequest)

        val contentLengthHeader = headResponse.headers.headerValue("Content-Length")
        val contentLength = contentLengthHeader?.toLongOrNull() ?: 0L

        val acceptRanges = headResponse.headers.headerValue("Accept-Ranges")
        val supportsRanges = acceptRanges == "bytes" || acceptRanges != "none"

        val outFileName = options.out ?: primaryUri.substringAfterLast("/")
        val outPath = options.dir?.let { fileOps.resolvePath(it, outFileName) } ?: outFileName

        if (contentLength > 0 && supportsRanges && options.split > 1 && contentLength > options.minSplitSize) {
            downloadInChunks(contentLength, outPath)
        } else {
            downloadSingle(primaryUri, outPath)
        }
    }

    private suspend fun downloadInChunks(contentLength: Long, outPath: String) = coroutineScope {
        val splitSize = maxOf(options.minSplitSize, contentLength / options.split)
        var offset = 0L

        val ranges = mutableListOf<HtxRange>()
        while (offset < contentLength) {
            val end = minOf(offset + splitSize - 1, contentLength - 1)
            ranges.add(HtxRange(offset, end))
            offset = end + 1
        }

        val semaphore = Semaphore(options.maxConcurrentDownloads)
        val fileMutex = Mutex()

        fileOps.write(outPath, ByteArray(0))

        ranges.forEachIndexed { index, range ->
            launch {
                semaphore.withPermit {
                    val mirrorUri = options.uris[index % options.uris.size]
                    val request = parseHtxRequest(mirrorUri, range = range)
                    val response = clientReactor.request(request)

                    fileMutex.withLock {
                        val existing = fileOps.readAllBytes(outPath)
                        val newContent = ByteArray(contentLength.toInt())
                        existing.copyInto(newContent)

                        val bytes = response.body.toArray()
                        bytes.copyInto(newContent, range.startInclusive.toInt())

                        fileOps.write(outPath, newContent)
                    }
                }
            }
        }
    }

    private suspend fun downloadSingle(uri: String, outPath: String) {
        val request = parseHtxRequest(uri)
        val response = clientReactor.request(request)
        fileOps.write(outPath, response.body.toArray())
    }
}
