package org.slf4j

interface Logger {
    fun info(s: CharSequence)
    fun debug(s: CharSequence)
    fun trace(s: CharSequence)
    fun warn(s: CharSequence)
    fun isInfoEnabled(): Boolean = true
    fun isDebugEnabled(): Boolean = true
    fun isTraceEnabled(): Boolean = true
    fun isWarnEnabled(): Boolean = true
    fun info(s: CharSequence, o: Any) = info("it $o")
    fun debug(s: CharSequence, o: Any) = debug("it $o")
    fun trace(s: CharSequence, o: Any) = trace("it $o")
    fun warn(s: CharSequence, o: Any) = warn("it $o")
    fun info(s: CharSequence, o: Any, o2: Any) = info("it $o $o2")
    fun debug(s: CharSequence, o: Any, o2: Any) = debug("it $o $o2")
    fun trace(s: CharSequence, o: Any, o2: Any) = trace("it $o $o2")
    fun warn(s: CharSequence, o: Any, o2: Any) = warn("it $o $o2")
    fun info(s: CharSequence, vararg o: Any) = info("it $o")
    fun debug(s: CharSequence, vararg o: Any) = debug("it $o")
    fun trace(s: CharSequence, vararg o: Any) = trace("it $o")
    fun warn(s: CharSequence, vararg o: Any) = warn("it $o")
}

