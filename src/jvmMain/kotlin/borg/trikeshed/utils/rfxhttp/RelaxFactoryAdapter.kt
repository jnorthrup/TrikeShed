package borg.trikeshed.utils.rfxhttp

import borg.trikeshed.couch.ConfixDocStore
import borg.trikeshed.couch.ViewServer
import borg.trikeshed.parse.confix.ConfixDoc
import borg.trikeshed.parse.confix.confixDoc
import borg.trikeshed.parse.confix.docAt

/**
 * Adapter bridging GWT RequestFactory JSON payloads into CouchStore
 * operations and evaluating queries via ViewServer.
 */
class RelaxFactoryAdapter(
    val store: ConfixDocStore,
    val viewServer: ViewServer
) {
    fun processIncomingRF(jsonPayload: String): String {
        try {
            if (jsonPayload.isBlank()) return "{}"
            val doc = confixDoc(jsonPayload)

            // Check for RequestFactory operations array in payload
            val operationsNode = doc.docAt("operations")
            if (operationsNode != null) {
                val idStr = System.currentTimeMillis().toString()
                store.put(idStr, jsonPayload)
                return "{\"status\":\"success\", \"id\":\"$idStr\"}"
            }

            store.put(System.currentTimeMillis().toString(), jsonPayload)
            return "{\"status\":\"processed\"}"
        } catch (e: Exception) {
            // Generalize the error message to avoid leaking internal trace details
            return "{\"status\":\"error\", \"message\":\"An error occurred processing the request.\"}"
        }
    }
}
