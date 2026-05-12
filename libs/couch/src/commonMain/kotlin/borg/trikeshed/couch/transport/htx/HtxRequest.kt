package borg.trikeshed.couch.transport.htx

data class HtxRequest(val method: CharSequence, val path: CharSequence, val accept: CharSequence)
