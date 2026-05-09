package borg.trikeshed.tls

actual fun createTlsEngine(settings: TlsSettings): TlsEngine = TlsEngineJdk(settings)
