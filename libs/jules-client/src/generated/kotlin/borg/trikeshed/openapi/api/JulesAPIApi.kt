package borg.trikeshed.openapi.api

/**
 * Generated from /Users/jim/work/TrikeShed/libs/jules-client/openapi/jules.openapi.yaml
 * by ./gradlew generateJulesSources.
 * Repository policy: this checked-in file must be regenerated, not edited by hand.
 */

import borg.trikeshed.openapi.infrastructure.GeneratedRequest
import borg.trikeshed.openapi.infrastructure.HttpMethod

/** Generated API interface for JulesAPI. */
interface JulesAPIApi {
    suspend fun createSession(): String
}

/** Default implementation — caller provides the low-level call. */
class DefaultJulesAPIApi(
   val call: suspend (GeneratedRequest) -> String,
) : JulesAPIApi {
    override suspend fun createSession(): String = call(JulesAPIApiContract.CreateSession.request)
}

/** Contract constants for each JulesAPI operation. */
object JulesAPIApiContract {
          object CreateSession {
              const val operationId: String = "createSession"
              val request: GeneratedRequest = GeneratedRequest(method = HttpMethod.POST, path = "/v1alpha/sessions")
          }
  
}