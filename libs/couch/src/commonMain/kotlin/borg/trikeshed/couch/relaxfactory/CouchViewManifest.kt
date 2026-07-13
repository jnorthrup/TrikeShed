package borg.trikeshed.couch.relaxfactory

import borg.trikeshed.couch.api.CouchDb11DesignDocument

data class CouchViewManifest(
    val databaseName: String,
    val designDocument: CouchDb11DesignDocument,
    val views: Map<String, CouchViewInvocation>,
)
