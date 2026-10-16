package borg.trikeshed.relaxfactory

import com.google.web.bindery.autobean.shared.AutoBeanCodex
import com.google.web.bindery.autobean.vm.AutoBeanFactorySource
import com.google.web.bindery.requestfactory.shared.RequestTransport
import com.google.web.bindery.requestfactory.shared.ServerFailure
import com.google.web.bindery.requestfactory.shared.messages.MessageFactory
import com.google.web.bindery.requestfactory.shared.messages.RequestMessage
import com.google.web.bindery.requestfactory.shared.messages.ResponseMessage

/** JVM RequestFactory's callback boundary; all admission and HTTP work lives in commonMain. */
class HtxRequestTransport(val element: RequestFactoryClientElement) : RequestTransport {
    companion object {
        val messages: MessageFactory = AutoBeanFactorySource.create(MessageFactory::class.java)
    }

    /** RF decodes before invoking its context failure receiver, so validate with that same codec. */
    fun validate(payload: String, response: String) {
        val request = AutoBeanCodex.decode(messages, RequestMessage::class.java, payload).`as`()
        val reply = AutoBeanCodex.decode(messages, ResponseMessage::class.java, response).`as`()
        if (reply.generalFailure != null) return
        reply.violations?.let {
            require(it.isNotEmpty()) { "RequestFactory response has an empty violations array" }
            return
        }
        val invocations = request.invocations?.size ?: 0
        val statuses: List<Boolean?> = reply.statusCodes ?: run {
            require(invocations == 0) { "RequestFactory response has no invocation statuses" }
            emptyList()
        }
        val results = reply.invocationResults ?: run {
            require(invocations == 0) { "RequestFactory response has no invocation results" }
            emptyList()
        }
        require(statuses.size == invocations && results.size == invocations) {
            "RequestFactory response cardinality differs from its invocations"
        }
        require(statuses.none { it == null }) { "RequestFactory response has a null invocation status" }
    }

    fun failure(receiver: RequestTransport.TransportReceiver, failure: Throwable) =
        receiver.onTransportFailure(ServerFailure(failure.message, failure.javaClass.name, null, true))

    override fun send(payload: String, receiver: RequestTransport.TransportReceiver) {
        element.send(payload) { result ->
            result.fold(
                onSuccess = { response ->
                    val decoderFailure = runCatching { validate(payload, response) }.exceptionOrNull()
                    if (decoderFailure != null) failure(receiver, decoderFailure)
                    else receiver.onTransportSuccess(response)
                },
                onFailure = { failure(receiver, it) },
            )
        }
    }
}
