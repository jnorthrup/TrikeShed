package org.xvm.cursor

import org.xvm.runtime.VmPointcutPublisher
import org.xvm.runtime.TypedefResolutionPublisher

object VmShutdownReifierHelper {
    @JvmStatic
    fun main(args: Array<String>) {
        // Initialize the resolution publisher so it hooks ServiceContext
        // and registers the shutdown hook.
        TypedefResolutionPublisher.subscribe()

        VmPointcutPublisher.active = true
        VmPointcutPublisher.publish(0x10, "test.Class.method1", 100)
        VmPointcutPublisher.publish(0x20, "test.Class.method2", 200)

        println("Helper generated events, exiting to trigger shutdown hook.")
    }
}
