package borg.trikeshed.mux

import borg.trikeshed.agent.modelmux.*
import borg.trikeshed.agent.modelmux.Projection
import borg.trikeshed.cas.*
import borg.trikeshed.job.*
import borg.trikeshed.lib.*
import borg.trikeshed.narsese.*
import borg.trikeshed.userspace.nio.spi.*
import kotlinx.coroutines.*
import java.nio.file.Files
import kotlin.test.*

class JevaOversightProviderTest {
    @Test fun oversightUsesTheSupervisorsEncoderAndFailsClosed() = runBlocking {
        val directory = Files.createTempDirectory("jeva-oversight-fixture")
        val backbone = Files.createDirectory(directory.resolve("backbone_mlx"))
        Files.writeString(directory.resolve("config.yaml"), "model:\n  pooling: last\n")
        Files.writeString(backbone.resolve("tokenizer.json"), "{}")
        Files.write(directory.resolve("head.pt"), _a[1.toByte()])
        var opened: String? = null
        val encoder = object : EncoderOperations {
            override fun open(directory: String, check: () -> Unit): EncoderSession {
                check()
                opened = directory
                error("synthetic encoder stopped before inference")
            }
        }
        val nio = NioSupervisor()
        nio.register(encoder)
        try {
            nio.open()
            val binding = jevaOversight(this, nio, JevaConfig(model = directory.toString(), device = "mlx", runtime = JevaRuntime.KOTLIN))
            try {
                val text = "A relevant fixture line."
                val excerpt = Excerpt("fixture", ContentId.of(text.encodeToByteArray()), 0, 0,
                    SourceRange(0, text.length), text, 1.0, _l.get<String>())
                val projection = Projection("fixture", "fixture", s_[excerpt], text.length, 0, 0)
                val receipt = binding.b.review("fixture", projection)
                assertEquals(backbone.toString(), opened)
                assertEquals(OversightStatus.UNAVAILABLE, receipt.status)
                assertTrue(receipt.verdicts.all { it == null })
                assertNull(binding.a.nio, "the shared supervisor should be reused rather than privately created")
            } finally {
                binding.a.drain()
            }
        } finally {
            nio.drain()
            Files.deleteIfExists(backbone.resolve("tokenizer.json"))
            Files.deleteIfExists(backbone)
            Files.deleteIfExists(directory.resolve("head.pt"))
            Files.deleteIfExists(directory.resolve("config.yaml"))
            Files.deleteIfExists(directory)
        }
    }
}
