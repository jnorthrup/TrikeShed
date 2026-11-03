package borg.trikeshed.narsese

import borg.trikeshed.lib.*
import borg.trikeshed.parse.jsonOf
import borg.trikeshed.userspace.nio.channels.spi.ProcessEnvironment
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*

/** Explicit opt-in; the fixture is intentionally too small to threaten host memory. */
class JevaNativeFootprintTest {
    @Test fun footprintWatchdogTerminatesAnOversizedWorker() {
        val worker = System.getenv("TRIKESHED_TEST_JEVA_WORKER")
        val model = System.getenv("TRIKESHED_TEST_JEVA_MODEL")
        assumeTrue(!worker.isNullOrBlank() && !model.isNullOrBlank(), "synthetic worker paths not supplied")
        val root = Path.of(requireNotNull(model))
        for (name in _l["config.yaml", "head.pt", "backbone_mlx/config.json", "backbone_mlx/tokenizer.json", "backbone_mlx/model.safetensors"]) {
            val path = root.resolve(name)
            require(Files.isRegularFile(path) && Files.size(path) in 1..65_536) { "Synthetic fixture is absent or too large: $name" }
        }
        val process = ProcessBuilder(requireNotNull(worker)).apply {
            environment().apply { clear(); putAll(ProcessEnvironment.curated()) }
        }.start()
        val reader = Executors.newSingleThreadExecutor()
        try {
            val request = jsonOf(_m["op" j "init", "config" j _m[
                "model" j root.toString(), "device" j "mlx", "max_state_length" j 64,
                "max_option_length" j 64, "timeout_ms" j 15_000,
                "mlx_max_tokens" j 64, "mlx_checkpoint_limit_bytes" j 65_536,
                "mlx_max_footprint_bytes" j 1,
            ]])
            process.outputStream.write((request + "\n").encodeToByteArray())
            process.outputStream.flush()
            val line = reader.submit<String?> { process.inputStream.bufferedReader().readLine() }.get(20, TimeUnit.SECONDS)
            assertNull(line, "The worker must not report a loaded model above its physical-footprint limit")
            assertTrue(process.waitFor(5, TimeUnit.SECONDS), "The footprint watchdog must terminate the child")
            assertNotEquals(0, process.exitValue())
            assertContains(process.errorStream.bufferedReader().readText(), "TRIKESHED_MLX_FOOTPRINT_LIMIT")
        } finally {
            if (process.isAlive) process.destroyForcibly()
            process.waitFor()
            reader.shutdownNow()
        }
    }

    @Test fun fullBlockingStderrCannotPreventFootprintRevocation() {
        val worker = System.getenv("TRIKESHED_TEST_JEVA_WORKER")
        val model = System.getenv("TRIKESHED_TEST_JEVA_MODEL")
        assumeTrue(!worker.isNullOrBlank() && !model.isNullOrBlank(), "synthetic worker paths not supplied")
        assumeTrue(Files.isExecutable(Path.of("/usr/bin/perl")), "system Perl is unavailable")
        val root = Path.of(requireNotNull(model))
        for (name in _l["config.yaml", "head.pt", "backbone_mlx/config.json", "backbone_mlx/tokenizer.json", "backbone_mlx/model.safetensors"]) {
            val path = root.resolve(name)
            require(Files.isRegularFile(path) && Files.size(path) in 1..65_536) { "Synthetic fixture is absent or too large: $name" }
        }
        // Fill stderr until EAGAIN, then restore its blocking flag before replacing
        // this launcher with the real native worker. The parent never drains it.
        val program = """
            use Fcntl qw(F_GETFL F_SETFL O_NONBLOCK);
            my ${'$'}flags = fcntl(STDERR, F_GETFL, 0); defined(${ '$' }flags) or die "get flags";
            fcntl(STDERR, F_SETFL, ${'$'}flags | O_NONBLOCK) or die "nonblocking";
            my ${'$'}block = 'x' x 4096; my ${'$'}filled = 0;
            while (1) { my ${'$'}n = syswrite(STDERR, ${'$'}block); last unless defined ${'$'}n; ${'$'}filled += ${'$'}n; }
            ${'$'}filled > 0 or die "empty pipe";
            fcntl(STDERR, F_SETFL, ${'$'}flags) or die "restore flags";
            syswrite(STDOUT, "READY\n") == 6 or die "ready";
            exec {${'$'}ARGV[0]} ${'$'}ARGV[0]; die "exec";
        """.trimIndent()
        val process = ProcessBuilder("/usr/bin/perl", "-e", program, requireNotNull(worker)).apply {
            environment().apply { clear(); putAll(ProcessEnvironment.curated()) }
        }.start()
        val reader = Executors.newSingleThreadExecutor()
        try {
            val stdout = process.inputStream.bufferedReader()
            assertEquals("READY", reader.submit<String?> { stdout.readLine() }.get(10, TimeUnit.SECONDS))
            val request = jsonOf(_m["op" j "init", "config" j _m[
                "model" j root.toString(), "device" j "mlx", "max_state_length" j 64,
                "max_option_length" j 64, "timeout_ms" j 15_000,
                "mlx_max_tokens" j 64, "mlx_checkpoint_limit_bytes" j 65_536,
                "mlx_max_footprint_bytes" j 1,
            ]])
            process.outputStream.write((request + "\n").encodeToByteArray())
            process.outputStream.flush()
            assertNull(reader.submit<String?> { stdout.readLine() }.get(20, TimeUnit.SECONDS))
            assertTrue(process.waitFor(5, TimeUnit.SECONDS), "A full stderr pipe must not prevent worker termination")
            assertNotEquals(0, process.exitValue())
        } finally {
            if (process.isAlive) process.destroyForcibly()
            process.waitFor()
            reader.shutdownNow()
        }
    }
}
