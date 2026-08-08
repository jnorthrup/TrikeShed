package org.trikeshed.oroboros

import borg.trikeshed.daemon.OroborosDaemon as LegacyDaemon

actual class OroborosDaemon {
    private var legacyDaemon: LegacyDaemon? = null
    actual fun startLoop() {
    }
    actual fun stopLoop() {
    }
}
