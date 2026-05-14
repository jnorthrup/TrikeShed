package borg.trikeshed.userspace.nio.tls

actual fun createTlsEngine(settings: borg.trikeshed.userspace.nio.tls.TlsSettings): borg.trikeshed.userspace.nio.tls.TlsEngine = TlsEngineJdk(settings)
