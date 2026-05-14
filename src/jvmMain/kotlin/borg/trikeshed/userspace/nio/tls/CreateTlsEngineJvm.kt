package borg.trikeshed.userspace.nio.tls

import borg.trikeshed.tls.TlsEngine
import borg.trikeshed.tls.TlsSettings

actual fun createTlsEngine(settings: TlsSettings): TlsEngine = TlsEngineJdk(settings)
