package mu

import borg.trikeshed.lib.*
import org.slf4j.Logger

class KLogger : Logger {
    fun debug(debugTxt: () -> CharSequence) {
        logDebug(debugTxt)
    }

    override fun debug(s: CharSequence) {
        logDebug { s }
    }

    override fun trace(s: CharSequence) {
        logDebug { s }
    }

    override fun info(s: CharSequence) = logDebug { s }
    fun info(function: () -> CharSequence) {
        debug(function)
    }

    override fun warn(s: CharSequence) = logDebug { s }
    fun warn(function: () -> CharSequence) = debug(function)

    fun error(s: CharSequence) = logDebug { s }
    fun error(function: () -> CharSequence) {
        debug(function)
    }
}

