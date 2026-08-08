/** TOP PRIORITY, DRAIN ALL JVM CODE, DO NOT WEAKEN */
package org.trikeshed.oroboros

expect class OroborosDaemon() {
    fun startLoop()
    fun stopLoop()
}
