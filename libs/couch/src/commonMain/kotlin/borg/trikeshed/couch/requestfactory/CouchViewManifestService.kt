package borg.trikeshed.couch.requestfactory

import borg.trikeshed.couch.relaxfactory.CouchViewManifest

/**
 * Bridges [CouchViewManifest] to [RequestFactoryTransportService].
 *
 * For each incoming [RequestFactoryCall], looks up the view by method name, resolves the
 * CouchDB query URL, calls [httpGet] to fetch the JSON response, then parses the result
 * back into a [RequestFactoryResponse].
 *
 * [httpGet] is caller-supplied so this class stays fully commonMain-compatible — no
 * platform HTTP library is imported here.
 */
class CouchViewManifestService(
    private val manifest: CouchViewManifest,
    private val httpGet: (path: String) -> String,
) : RequestFactoryTransportService {

    override fun invoke(call: RequestFactoryCall): RequestFactoryResponse {
        val invocation = manifest.views[call.method]
            ?: return RequestFactoryResponse(
                success = false,
                value = TransportValue.StringValue("unknown view: ${call.method}"),
            )

        val args = call.arguments.map { it.unwrap() }.toTypedArray()
        val resolved = invocation.invoke(*args)

        val responseJson = httpGet(resolved.path)

        val parsed = RequestFactoryJsonCodec.parseObject(responseJson)
        val value = RequestFactoryJsonCodec.toTransportValue(parsed)
        return RequestFactoryResponse(success = true, value = value)
    }
}
