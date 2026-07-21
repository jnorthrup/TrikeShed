package borg.trikeshed.forge.gallery

import org.w3c.fetch.RequestInit
import org.w3c.fetch.Headers
import kotlinx.browser.window
import kotlin.js.Promise

class GalleryReactorClient(private val endpointUrl: String = "/api/invoke") {
    
    fun sendAction(actionPayload: String): Promise<dynamic> {
        val headers = Headers()
        headers.append("Content-Type", "application/json")
        
        val init = js("{}")
        init.method = "POST"
        init.headers = headers
        init.body = actionPayload
        
        return window.fetch(endpointUrl, init as RequestInit)
            .then { response -> 
                if (response.ok) {
                    response.json()
                } else {
                    Promise.reject(Exception("Reactor returned \${response.status}"))
                }
            }
    }

    fun subscribeToBlackboard(onEvent: (dynamic) -> Unit) {
        // Fallback polling fetch simulation since full websocket subscription implies long-polling or ws not implemented in NodeFetchReactorEndpoint natively
        window.setInterval({
            if (window.navigator.onLine) {
                val action = """{"verb":"subscribe","nuid":{"capabilityCat":"blackboard","nonceBytes":"","subnet":"global.mesh"},"payload":""}"""
                sendAction(action).then { response ->
                    onEvent(response)
                }.catch { err ->
                    console.log("Subscription poll failed: \$err")
                }
            }
        }, 5000)
    }
}
