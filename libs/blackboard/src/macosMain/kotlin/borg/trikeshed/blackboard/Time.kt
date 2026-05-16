package borg.trikeshed.blackboard

import platform.Foundation.CFAbsoluteTimeGetCurrent
import platform.darwin.NSEC_PER_SEC

actual fun currentTimeMillis(): Long {
    // CFAbsoluteTimeGetCurrent() returns time since 2001-01-01
    // Convert to milliseconds since 1970-01-01 (Unix epoch)
    val cocoaTime = CFAbsoluteTimeGetCurrent()
    val unixTime = cocoaTime + 978307200.0 // 978307200 seconds between 2001-01-01 and 1970-01-01
    return (unixTime * 1000).toLong()
}
