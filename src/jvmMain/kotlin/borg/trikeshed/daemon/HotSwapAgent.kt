package borg.trikeshed.daemon

import java.io.File
import java.lang.instrument.ClassFileTransformer
import java.lang.instrument.Instrumentation
import java.security.ProtectionDomain

/**
 * Java agent that watches a target `.class` file for mtime changes and, on
 * change, calls [Instrumentation.retransformClasses] to swap the new bytecode
 * into the running JVM. The agent holds a single global [FileWatcher]
 * instance keyed by class name → file path; the daemon passes
 * `-javaagent:hotswap-agent.jar=ClassName:Path/to/Class.class` at start.
 *
 * Usage:
 *   java -javaagent:build/libs/hotswap-agent.jar=borg.trikeshed.daemon.CycleBody:build/classes/.../CycleBody.class \
 *        -cp ... borg.trikeshed.daemon.OroborosDaemon ...
 *
 * Side effect: prints one line per successful redefine to stdout, prefix
 * `[HOTSWAP-AGENT]`. The daemon's own `[HOTSWAP]` line tells you the new
 * bytecode is being executed.
 */
object HotSwapAgent {

    @JvmStatic
    fun premain(args: String?, inst: Instrumentation) {
        val parsed = parseArgs(args)
        if (parsed.isEmpty()) {
            System.err.println("[HOTSWAP-AGENT] no class:path pairs; agent idle")
            return
        }
        if (!inst.isRetransformClassesSupported) {
            System.err.println("[HOTSWAP-AGENT] retransformClasses not supported on this JVM; agent idle")
            return
        }
        for ((className, classFile) in parsed) {
            val watcher = FileWatcher(className, classFile, inst)
            watcher.start()
            System.err.println("[HOTSWAP-AGENT] watching $className <= $classFile")
        }
    }

    private data class Target(val className: String, val classFile: File)

    private fun parseArgs(args: String?): List<Target> {
        if (args.isNullOrBlank()) return emptyList()
        return args.split(',').mapNotNull { spec ->
            val parts = spec.split(':')
            if (parts.size != 2) return@mapNotNull null
            val cls = parts[0].trim()
            val file = File(parts[1].trim()).absoluteFile
            if (cls.isEmpty() || !file.exists()) return@mapNotNull null
            Target(cls, file)
        }
    }

    private class FileWatcher(
        private val className: String,
        private val classFile: File,
        private val inst: Instrumentation,
    ) : Thread("hotswap-watcher-${className.takeLast(20)}") {
        @Volatile private var lastMtime = ((classFile.lastModified() / 1000L) * 1000L)

        init { isDaemon = true }

        override fun run() {
            while (!currentThread().isInterrupted) {
                try {
                    sleep(200)
                    val mtime = ((classFile.lastModified() / 1000L) * 1000L)
                    if (mtime != lastMtime && mtime > 0) {
                        lastMtime = mtime
                        redefine()
                    }
                } catch (_: InterruptedException) {
                    return
                } catch (t: Throwable) {
                    System.err.println("[HOTSWAP-AGENT] watcher error: ${t.message}")
                }
            }
        }

        private fun redefine() {
            try {
                val cls = Class.forName(className)
                val bytes = classFile.readBytes()
                val transformer = object : ClassFileTransformer {
                    override fun transform(
                        loader: ClassLoader?,
                        name: String?,
                        classBeingRedefined: Class<*>?,
                        protectionDomain: ProtectionDomain?,
                        classfileBuffer: ByteArray?,
                    ): ByteArray = if (name == className.replace('.', '/')) bytes else classfileBuffer!!
                }
                inst.addTransformer(transformer, true)
                try {
                    inst.retransformClasses(cls)
                    println("[HOTSWAP-AGENT] retransformed $className (${bytes.size} bytes, mtime=$lastMtime)")
                } finally {
                    inst.removeTransformer(transformer)
                }
            } catch (t: Throwable) {
                System.err.println("[HOTSWAP-AGENT] redefine FAILED for $className: ${t.javaClass.simpleName}: ${t.message?.take(200)}")
            }
        }
    }
}
