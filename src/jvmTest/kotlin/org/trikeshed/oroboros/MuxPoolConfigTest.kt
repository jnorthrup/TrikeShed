package borg.trikeshed.daemon

import borg.trikeshed.agent.modelmux.MuxPool
import borg.trikeshed.lib.*
import kotlin.test.*

class MuxPoolConfigTest {
    @Test fun daemonAcceptsSeveralPoolsForOneBinding() {
        val config = OroborosDaemon.parseConfig(_a[
            "--mux-pool", "account=2:key:first",
            "--mux-pool", "device=1:key:first",
            "--mux-pool", "device=1:key:first",
        ])
        assertEquals(MuxPool("account", 2), config.muxPools["key:first"])
        assertEquals(_l[MuxPool("device", 1)], config.muxAdditionalPools["key:first"])
        assertFailsWith<IllegalArgumentException> { OroborosDaemon.parseConfig(_a[
            "--mux-pool", "device=1:key:first", "--mux-pool", "device=2:key:second",
        ]) }
    }

    @Test fun daemonAcceptsExplicitBindingsForOnePool() {
        val config = OroborosDaemon.parseConfig(arrayOf(
            "--mux-pool", "account=2:key:llm.first.key",
            "--mux-pool", "account=2:endpoint:https://host.invalid:8443/v1",
        ))
        assertEquals(mapOf(
            "key:llm.first.key" to MuxPool("account", 2),
            "endpoint:https://host.invalid:8443/v1" to MuxPool("account", 2),
        ), config.muxPools)
    }

    @Test fun rejectsInvalidAndConflictingAssignments() {
        for (specs in listOf(
            listOf("account=0:key:first"),
            listOf("account=1:key:"),
            listOf("account=1:endpoint:https://host.invalid/"),
            listOf("account=1:key:first", "account=2:key:second"),
            listOf("one=1:key:first", "two=1:key:first"),
        )) assertFailsWith<IllegalArgumentException> { OroborosDaemon.muxPools(specs) }
    }
}
