package borg.trikeshed.hermes

import borg.trikeshed.vm.Teleported
import borg.trikeshed.lib.get
import borg.trikeshed.lib.toList
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class HermesPythonPortTest {
    @Test
    fun nativeBlocksPropagateAndProjectToBlackboard() {
        HermesPythonPort().use { port ->
            val inventory = port.inventorySources(mapOf(
                "portable" to ("portable.py" to "VALUE = 41"),
                "native_leaf" to ("native_leaf.py" to "import cryptography\nVALUE = 1"),
                "consumer" to ("consumer.py" to "from native_leaf import VALUE"),
            ))

            assertEquals(HermesModuleStatus.READY, inventory.modules.getValue("portable").status)
            assertEquals(HermesModuleStatus.BLOCKED_NATIVE, inventory.modules.getValue("native_leaf").status)
            assertEquals(HermesModuleStatus.BLOCKED_TRANSITIVE, inventory.modules.getValue("consumer").status)
            assertTrue(port.blackboard().has("hermes/python/module/native_leaf"))
            assertTrue(port.blackboard().has("hermes/python/triage"))
        }
    }

    @Test
    fun readyModulesImportThroughNoNativeBlackboardVm() {
        HermesPythonPort().use { port ->
            val inventory = port.inventorySources(mapOf(
                "demo" to ("demo/__init__.py" to "from .maths import answer"),
                "demo.maths" to ("demo/maths.py" to "answer = 40 + 2"),
            ))
            assertEquals(Teleported.Bool(true), port.importInVm(inventory, "demo"))
            assertTrue(port.blackboard().has("hermes/python/pointcut/import/demo"))
            assertTrue(port.blackboard().has("hermes/python/pointcut/import/demo.maths"))
        }
    }

    @Test
    fun blockedEntryNeverStartsTheVm() {
        HermesPythonPort().use { port ->
            val inventory = port.inventorySources(mapOf(
                "unsafe" to ("unsafe.py" to "from pydantic_core import SchemaValidator"),
            ))
            val failure = assertFailsWith<IllegalArgumentException> { port.importInVm(inventory, "unsafe") }
            assertTrue(failure.message.orEmpty().contains("pydantic_core"))
        }
    }

    @Test
    fun deferredNativeImportIsTriagedWithoutBlockingModuleLoad() {
        HermesPythonPort().use { port ->
            val inventory = port.inventorySources(mapOf(
                "lazy" to ("lazy.py" to "def later():\n    import cryptography\n    return 1"),
            ))
            val module = inventory.modules.getValue("lazy")
            assertEquals(HermesModuleStatus.READY, module.status)
            assertEquals(setOf("cryptography"), module.deferredImports)
            assertEquals(Teleported.Bool(true), port.importInVm(inventory, "lazy"))
        }
    }

    @Test
    fun sleeveShadowsBannedModuleAndUnblocksItsConsumers() {
        HermesPythonPort().use { port ->
            val inventory = port.inventorySources(
                sources = mapOf(
                    "app" to ("app.py" to "import pydantic\nVALUE = pydantic.PORTABLE"),
                ),
                sleeveSources = mapOf(
                    "pydantic" to ("pydantic/__init__.py" to "PORTABLE = 42"),
                ),
            )
            assertEquals(HermesModuleStatus.READY, inventory.modules.getValue("app").status)
            assertTrue(inventory.modules.getValue("pydantic").sleeved)
            assertEquals(Teleported.Bool(true), port.importInVm(inventory, "app"))
            assertTrue(port.blackboard().has("hermes/python/pointcut/import/pydantic"))
        }
    }

    @Test
    fun significantGapsComeFromOntologyZoomCounts() {
        HermesPythonPort().use { port ->
            val inventory = port.inventorySources(mapOf(
                "native" to ("native.py" to "import pydantic"),
                "consumer" to ("consumer.py" to "import native"),
                "later" to ("later.py" to "def load():\n    import pydantic"),
            ))
            val gap = inventory.significantGaps()[0]
            assertEquals("pydantic", gap.root)
            assertEquals(2, gap.impacted)
            assertEquals(1, gap.direct)
            assertEquals(1, gap.deferred)
            assertEquals(listOf("consumer", "native"), gap.modules.toList())
            assertTrue(inventory.ontology.lines.toList().contains("blocked/pydantic/consumer"))
        }
    }

    @Test
    fun curatedYamlAndDotenvSleevesExecuteInsideBtrfsVfs() {
        val sleeveRoot = Path.of("graalpy-sleeve/hermes")
        val yaml = Files.readString(sleeveRoot.resolve("yaml.py"))
        val dotenv = Files.readString(sleeveRoot.resolve("dotenv/__init__.py"))
        HermesPythonPort().use { port ->
            val inventory = port.inventorySources(
                sources = mapOf(
                    "app" to ("app.py" to """
                        import yaml
                        from dotenv import dotenv_values
                        from io import StringIO
                        assert yaml.safe_load('answer: 42')['answer'] == 42
                        assert dotenv_values(stream=StringIO('KEY=value\n'))['KEY'] == 'value'
                    """.trimIndent()),
                ),
                sleeveSources = mapOf(
                    "yaml" to ("yaml.py" to yaml),
                    "dotenv" to ("dotenv/__init__.py" to dotenv),
                ),
            )
            assertEquals(Teleported.Bool(true), port.importInVm(inventory, "app"))
            assertTrue(port.blackboard().has("hermes/python/vfs"))
        }
    }
}