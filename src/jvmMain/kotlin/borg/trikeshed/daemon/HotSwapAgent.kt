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
        val dir = args?.let { File(it) }
        if (dir == null) {
            System.err.println("[HOTSWAP-AGENT] no directory specified; agent idle")
            return
        }
        if (!inst.isRetransformClassesSupported) {
            System.err.println("[HOTSWAP-AGENT] retransformClasses not supported on this JVM; agent idle")
            return
        }
        val watcher = DirectoryWatcher(dir, inst)
        watcher.start()
        System.err.println("[HOTSWAP-AGENT] watching ${dir.absolutePath}/.generation")
    }

    private class DirectoryWatcher(
        private val watchDir: File,
        private val inst: Instrumentation,
    ) : Thread("hotswap-watcher") {
        private val genFile = File(watchDir, ".generation")
        @Volatile private var lastMtime = 0L

        init { 
            isDaemon = true
            if (genFile.exists()) {
                lastMtime = (genFile.lastModified() / 1000L) * 1000L
            }
        }

        override fun run() {
            while (!currentThread().isInterrupted) {
                try {
                    sleep(200)
                    if (!genFile.exists()) continue
                    val mtime = (genFile.lastModified() / 1000L) * 1000L
                    if (mtime != lastMtime && mtime > 0) {
                        val prevMtime = lastMtime
                        lastMtime = mtime
                        redefineAll(prevMtime)
                    }
                } catch (_: InterruptedException) {
                    return
                } catch (t: Throwable) {
                    System.err.println("[HOTSWAP-AGENT] watcher error: ${t.message}")
                }
            }
        }

        private fun redefineAll(prevGenMtime: Long) {
            try {
                val generation = genFile.readText().trim()
                
                var retransformed = 0
                var skipped = 0

                val loadedClasses = inst.allLoadedClasses

                // Class names are relative to the classes ROOT (watchDir/classes), not watchDir:
                // relativizing against watchDir yielded "classes.borg…" names that matched no
                // loaded class — every generation retransformed 0.
                val classesRoot = File(watchDir, "classes")
                classesRoot.walkTopDown().filter { it.isFile && it.name.endsWith(".class") }.forEach { classFile ->
                    val classMtime = (classFile.lastModified() / 1000L) * 1000L
                    if (classMtime > prevGenMtime) {
                        val relativePath = classFile.relativeTo(classesRoot).path
                        val className = relativePath.removeSuffix(".class").replace(File.separatorChar, '.')

                        val cls = loadedClasses.find { it.name == className }
                        if (cls != null) {
                            val bytes = classFile.readBytes()
                            val transformer = object : ClassFileTransformer {
                                override fun transform(
                                    loader: ClassLoader?,
                                    name: String?,
                                    classBeingRedefined: Class<*>?,
                                    protectionDomain: ProtectionDomain?,
                                    classfileBuffer: ByteArray?,
                                ): ByteArray? = if (name == className.replace('.', '/')) bytes else null
                            }
                            inst.addTransformer(transformer, true)
                            try {
                                inst.retransformClasses(cls)
                                retransformed++
                            } catch (e: UnsupportedOperationException) {
                                skipped++
                            } finally {
                                inst.removeTransformer(transformer)
                            }
                        }
                    }
                }
                println("[HOTSWAP-AGENT] generation $generation: retransformed $retransformed classes, skipped $skipped (schema change).")
            } catch (t: Throwable) {
                System.err.println("[HOTSWAP-AGENT] redefineAll FAILED: ${t.javaClass.simpleName}: ${t.message?.take(200)}")
            }
        }
    }
}
