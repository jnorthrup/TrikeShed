package borg.trikeshed.relaxfactory

import borg.trikeshed.htx.*
import borg.trikeshed.lib.*
import borg.trikeshed.parse.jsonOf
import borg.trikeshed.reactor.JvmTlsCodecBackend
import borg.trikeshed.userspace.nio.channels.spi.JvmChannelOperations
import com.google.web.bindery.event.shared.EventBus
import com.google.web.bindery.event.shared.SimpleEventBus
import com.google.web.bindery.requestfactory.shared.*
import com.google.web.bindery.requestfactory.vm.RequestFactorySource
import kotlinx.coroutines.*

/** The original RequestFactory client; HTX owns its HTTP transport and admitted work. */
class HtxRequestFactoryClient(
    val factory: CouchGwtFactory,
    val transport: HtxRequestTransport,
) {
    suspend fun <T> await(request: Request<T>): T? {
        val receipt = CompletableDeferred<T?>()
        request.to(object : Receiver<T>() {
            override fun onSuccess(response: T?) { receipt.complete(response) }
            override fun onFailure(error: ServerFailure) {
                receipt.completeExceptionally(IllegalStateException(error.message))
            }
        }).fire(object : Receiver<Void>() {
            override fun onSuccess(response: Void?) {
                if (!receipt.isCompleted) receipt.completeExceptionally(IllegalStateException("RequestFactory returned no invocation result"))
            }
            override fun onFailure(error: ServerFailure) {
                receipt.completeExceptionally(IllegalStateException(error.message))
            }
        })
        return receipt.await()
    }

    suspend fun close() = transport.element.drain()

    suspend fun execute(arguments: Array<String>): String {
        require(arguments.isNotEmpty()) { USAGE }
        return when (arguments[0]) {
            "find" -> {
                require(arguments.size == 2) { USAGE }
                render(await(factory.couch().get(arguments[1])))
            }
            "persist" -> {
                require(arguments.size in 3..4) { USAGE }
                val id = arguments[1]
                val loaded = await(factory.couch().get(id))
                revision(loaded, arguments.getOrNull(3))
                val context = factory.couch()
                val document = if (loaded == null) context.create(CouchDocumentProxy::class.java).also { it.setId(id) }
                    else context.edit(loaded)
                document.setJson(arguments[2])
                val saved = requireNotNull(await(context.persist(document))) { "RequestFactory returned no persisted document" }
                jsonOf(mapOf("ok" to true, "id" to saved.getId(), "rev" to saved.getRev()))
            }
            "delete" -> {
                require(arguments.size in 2..3) { USAGE }
                val loaded = requireNotNull(await(factory.couch().get(arguments[1]))) { "Couch document is missing" }
                revision(loaded, arguments.getOrNull(2))
                val context = factory.couch()
                jsonOf(mapOf("ok" to await(context.delete(context.edit(loaded))), "id" to loaded.getId()))
            }
            "view" -> {
                require(arguments.size in 3..4) { USAGE }
                requireNotNull(await(factory.couch().storedView(arguments[1], arguments[2], arguments.getOrNull(3) ?: "")))
            }
            else -> throw IllegalArgumentException(USAGE)
        }
    }

    companion object {
        const val USAGE = "<endpoint> find <id> | persist <id> <json> [revision] | delete <id> [revision] | view <design> <view> [query]"

        suspend fun open(
            endpoint: String,
            client: HtxClientReactorElement,
            eventBus: EventBus = SimpleEventBus(),
        ): HtxRequestFactoryClient {
            val element = RequestFactoryClientElement(endpoint, client, currentCoroutineContext()[Job])
            try {
                element.open()
                val transport = HtxRequestTransport(element)
                val factory = RequestFactorySource.create(CouchGwtFactory::class.java)
                factory.initialize(eventBus, transport)
                return HtxRequestFactoryClient(factory, transport)
            } catch (failure: Throwable) {
                try { element.drain() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
                throw failure
            }
        }

        fun revision(document: CouchDocumentProxy?, expected: String?) {
            require(expected == null || document?.getRev() == expected) { "Couch revision conflict" }
        }

        fun render(document: CouchDocumentProxy?): String = if (document == null) "null" else jsonOf(
            ConfixBean.from(document.getJson() ?: "{}").fields() + mapOf("_id" to document.getId(), "_rev" to document.getRev()),
        )

        suspend fun run(arguments: Array<String>): String {
            require(arguments.size >= 2) { USAGE }
            val reactor = HtxReactorElement(JvmChannelOperations(), JvmTlsCodecBackend(), parentJob = currentCoroutineContext()[Job])
            try {
                reactor.open()
                val http = openHtxClientReactorElement(routeService = reactor, options = HtxClientOptions(maxRedirects = 0),
                    parentJob = currentCoroutineContext()[Job])
                try {
                    val client = open(arguments[0], http)
                    try { return client.execute(arguments.copyOfRange(1, arguments.size)) }
                    finally { withContext(NonCancellable) { client.close() } }
                } finally { withContext(NonCancellable) { http.close() } }
            } finally { withContext(NonCancellable) { reactor.close() } }
        }

        @JvmStatic
        fun main(arguments: Array<String>) = runBlocking {
            println(run(arguments))
        }
    }
}
