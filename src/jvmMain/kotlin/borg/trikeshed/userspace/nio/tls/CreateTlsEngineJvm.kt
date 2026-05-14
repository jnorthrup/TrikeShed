package borg.trikeshed.userspace.nio.tls

actual fun createTlsEngine(settings: TlsSettings): TlsEngine = TlsEngineJdk(settings)
