package borg.trikeshed.util.oroboros

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import borg.trikeshed.userspace.nio.platform.spi.SystemOperations
import kotlin.coroutines.CoroutineContext

class DummySysOps(override val homedir: String) : SystemOperations {
    override fun getenv(name: String, defaultVal: String?) = defaultVal
    override fun getProperty(name: String, defaultVal: String?) = defaultVal
}

class ForgeHomeTest {
    @Test
    fun testDefaultPath() {
        val sysOps = DummySysOps("/home/user")
        val fh = ForgeHome(sysOps)
        assertEquals("/home/user/.local/forge_home", fh.basePath)
    }

    @Test
    fun testSafeAgentNamespace() {
        val fh = ForgeHome(DummySysOps("/h"))
        assertEquals("/h/.local/forge_home/agents/agent1/foo/bar", fh.resolveAgentPath("agent1", "foo/bar"))
        assertEquals("/h/.local/forge_home/agents/agent1", fh.resolveAgentPath("agent1", ""))
        assertEquals("/h/.local/forge_home/agents/agent1/baz", fh.resolveAgentPath("agent1", "./baz"))
    }

    @Test
    fun testRejection() {
        val fh = ForgeHome(DummySysOps("/h"))

        // Agent ID rejection
        assertFailsWith<IllegalArgumentException> { fh.resolveAgentPath("", "foo") }
        assertFailsWith<IllegalArgumentException> { fh.resolveAgentPath("a/b", "foo") }
        assertFailsWith<IllegalArgumentException> { fh.resolveAgentPath("..", "foo") }

        // Subpath rejection
        assertFailsWith<IllegalArgumentException> { fh.resolveAgentPath("a", "/foo") }
        assertFailsWith<IllegalArgumentException> { fh.resolveAgentPath("a", "foo/../bar") }
        assertFailsWith<IllegalArgumentException> { fh.resolveAgentPath("a", "foo/.git/bar") }
        assertFailsWith<IllegalArgumentException> { fh.resolveAgentPath("a", ".pijul/bar") }
        assertFailsWith<IllegalArgumentException> { fh.resolveAgentPath("a", "bar/.oroboros") }
    }
}
