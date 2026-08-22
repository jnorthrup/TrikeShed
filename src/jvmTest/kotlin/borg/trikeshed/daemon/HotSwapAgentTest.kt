package borg.trikeshed.daemon

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class HotSwapAgentTest {

    @Test
    fun testHotSwapMechanics() {
        // Setup working directories
        val baseDir = File(System.getProperty("java.io.tmpdir"), "hotswap-agent-test-${System.currentTimeMillis()}")
        val srcDir = File(baseDir, "src")
        val outDir = File(baseDir, "out")
        srcDir.mkdirs()
        outDir.mkdirs()

        try {
            // We use standard javac for simplicity and robustness in testing the agent.
            // Create V1 of a simple java class
            val v1Src = """
                package dummy;
                public class LoopPrint {
                    public static String getConstant() { return "V1"; }
                    public static void main(String[] args) throws Exception {
                        while (true) {
                            System.out.println(getConstant());
                            Thread.sleep(500);
                        }
                    }
                }
            """.trimIndent()
            
            val srcFile = File(srcDir, "dummy/LoopPrint.java")
            srcFile.parentFile.mkdirs()
            srcFile.writeText(v1Src)
            
            // Compile V1
            val javacV1 = ProcessBuilder("javac", "-d", outDir.absolutePath, srcFile.absolutePath).start()
            assertTrue(javacV1.waitFor(10, TimeUnit.SECONDS), "javac V1 should finish")
            assertEquals(0, javacV1.exitValue(), "javac V1 should succeed")
            
            val classFile = File(outDir, "dummy/LoopPrint.class")
            assertTrue(classFile.exists(), "class file should be created")

            // Touch .generation to V1
            val genFile = File(outDir, ".generation")
            genFile.writeText("1\n")
            
            // Agent jar is expected in build/libs/hotswap-agent.jar (from step 1)
            val agentJar = File("build/libs/hotswap-agent.jar").absoluteFile
            assertTrue(agentJar.exists(), "hotswap-agent.jar must exist")

            val kotlinStdlibJar = System.getProperty("java.class.path").split(File.pathSeparator).find { it.contains("kotlin-stdlib") }
            val classpath = if (kotlinStdlibJar != null) {
                "${outDir.absolutePath}${File.pathSeparator}$kotlinStdlibJar"
            } else {
                outDir.absolutePath
            }

            // Start child process
            val pb = ProcessBuilder(
                "java",
                "-javaagent:${agentJar.absolutePath}=${outDir.absolutePath}",
                "-cp", classpath,
                "dummy.LoopPrint"
            )
            pb.redirectErrorStream(true)
            val child = pb.start()

            val output = mutableListOf<String>()
            val readerThread = thread(isDaemon = true) {
                child.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        synchronized(output) {
                            output.add(line)
                            println("CHILD: $line")
                        }
                    }
                }
            }

            // Wait for V1 output
            var v1Seen = false
            for (i in 1..20) {
                Thread.sleep(500)
                synchronized(output) {
                    if (output.any { it == "V1" }) {
                        v1Seen = true
                    }
                }
                if (v1Seen) break
            }
            if (!v1Seen) {
               child.destroyForcibly()
               child.waitFor()
               fail<Unit>("Child should print V1, output: ${output.joinToString(", ")}")
            }

            // Now write V2
            val v2Src = v1Src.replace("\"V1\"", "\"V2\"")
            srcFile.writeText(v2Src)
            
            // Compile V2 to a temp out dir to avoid incomplete class files while reading
            val outDir2 = File(baseDir, "out2")
            outDir2.mkdirs()
            val javacV2 = ProcessBuilder("javac", "-d", outDir2.absolutePath, srcFile.absolutePath).start()
            assertTrue(javacV2.waitFor(10, TimeUnit.SECONDS), "javac V2 should finish")
            assertEquals(0, javacV2.exitValue(), "javac V2 should succeed")
            
            val classFile2 = File(outDir2, "dummy/LoopPrint.class")
            
            // Wait slightly to ensure mtime is strictly greater (1s resolution on some filesystems)
            Thread.sleep(1500)

            // Copy over the class file
            classFile2.copyTo(classFile, overwrite = true)
            
            // Bump generation
            genFile.writeText("2\n")

            // Wait for V2 output
            var v2Seen = false
            for (i in 1..20) {
                Thread.sleep(500)
                synchronized(output) {
                    if (output.any { it == "V2" }) {
                        v2Seen = true
                    }
                }
                if (v2Seen) break
            }
            
            child.destroyForcibly()
            child.waitFor()
            readerThread.join(2000)

            assertTrue(v2Seen, "Child should print V2 after hotswap, got output: ${output.joinToString(", ")}")
            assertTrue(output.any { it.contains("generation 2: retransformed 1 classes") }, "Agent should log retransform")

        } finally {
            baseDir.deleteRecursively()
        }
    }
}
