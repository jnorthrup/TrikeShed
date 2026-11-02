package borg.trikeshed.pi

import borg.trikeshed.lib.*
import java.nio.file.Files
import kotlin.test.*

class PiWorkspaceTest {
    @Test fun exactEditsRejectStaleAmbiguousAndUnownedContent() {
        val root = Files.createTempDirectory("pi-source")
        try {
            val source = root.resolve("agent.ts")
            Files.writeString(source, "const label = 'Pi';\n")
            val workspace = PiWorkspace(root, _s["agent.ts"])
            assertTrue(workspace.dispatch("read", _m["path" j "agent.ts"]).contains("const label"))
            val edit = _m["path" j "agent.ts", "oldText" j "'Pi'", "newText" j "'Graal Pi'"]
            assertTrue(workspace.dispatch("edit", edit).contains("diff"))
            assertEquals("const label = 'Graal Pi';\n", Files.readString(source))
            assertFails { workspace.dispatch("edit", edit) }
            assertFails { workspace.dispatch("read", _m["path" j "../agent.ts"]) }
            assertFails { workspace.dispatch("read", _m["path" j "secret.ts"]) }
            workspace.dispatch("read", _m["path" j "agent.ts"])
            Files.writeString(source, "// external change\nconst label = 'Graal Pi';\n")
            assertFails { workspace.dispatch("edit", _m["path" j "agent.ts", "oldText" j "'Graal Pi'", "newText" j "'Pi'"]) }
            Files.writeString(source, "Pi Pi")
            workspace.dispatch("read", _m["path" j "agent.ts"])
            assertFails { workspace.dispatch("edit", _m["path" j "agent.ts", "oldText" j "Pi", "newText" j "x"]) }
            assertEquals("Pi Pi", Files.readString(source))
        } finally { root.toFile().deleteRecursively() }
    }
    @Test fun symlinksInvalidUtf8AndOversizeSourcesAreRefused() {
        val root = Files.createTempDirectory("pi-source")
        val outside = Files.createTempDirectory("pi-outside")
        try {
            val source = root.resolve("agent.js")
            val secret = outside.resolve("secret.js")
            Files.writeString(secret, "outside")
            Files.createSymbolicLink(source, secret)
            val workspace = PiWorkspace(root, _s["agent.js", "nested/agent.js"])
            assertFails { workspace.dispatch("read", _m["path" j "agent.js"]) }
            assertFails { workspace.dispatch("edit", _m["path" j "agent.js", "oldText" j "outside", "newText" j "oops"]) }
            assertEquals("outside", Files.readString(secret))
            Files.createSymbolicLink(root.resolve("nested"), outside)
            assertFails { workspace.dispatch("read", _m["path" j "nested/agent.js"]) }
            Files.delete(source)
            Files.write(source, _a[0xc3.toByte(), 0x28.toByte()])
            assertFails { workspace.dispatch("read", _m["path" j "agent.js"]) }
            Files.writeString(source, "x".repeat(PiWorkspace.BYTES + 1))
            assertFails { workspace.dispatch("read", _m["path" j "agent.js"]) }
            assertFails { PiWorkspace(root, _s[".env.js"]) }
            assertFails { PiWorkspace(root, _s["../agent.js"]) }
        } finally { root.toFile().deleteRecursively(); outside.toFile().deleteRecursively() }
    }
}
