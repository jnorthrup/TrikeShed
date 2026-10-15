package borg.trikeshed.graal.subvm

import borg.trikeshed.hermes.*
import borg.trikeshed.vm.*
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

class PythonWheelsTest {
    @TempDir lateinit var directory: Path

    private fun wheel(entries: Map<String, String>, pure: Boolean = true): Path {
        val root = Files.createTempDirectory(directory, "module-")
        val lib = Files.createDirectory(root.resolve("lib"))
        val archive = lib.resolve("example-1.0-py3-none-any.whl")
        ZipOutputStream(Files.newOutputStream(archive)).use { zip ->
            for ((name, content) in entries + ("example-1.0.dist-info/WHEEL" to
                "Wheel-Version: 1.0\nRoot-Is-Purelib: $pure\nTag: py3-none-any\n")) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.encodeToByteArray())
                zip.closeEntry()
            }
        }
        val bytes = Files.readAllBytes(archive)
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        Files.writeString(root.resolve("MANIFEST.tsv"), GuestModuleManifest(PythonWheels.MODULE,
            entries = listOf(GuestModuleEntry(archive.fileName.toString(), bytes.size.toLong(), hash))).render())
        return root
    }

    @Test fun verifiedNamespaceModulesAndResourcesRunInsideBtrfs() {
        val dependencies = PythonWheels.read(wheel(mapOf(
            "vendor/example/__init__.py" to "from .values import VALUE\nfrom pathlib import Path\nDATA = Path(__file__).with_name('sample.txt').read_text()",
            "vendor/example/values.py" to "VALUE = 42",
            "vendor/example/sample.txt" to "retained resource",
        )))
        HermesPythonPort().use { port ->
            val inventory = port.inventorySources(mapOf("entry" to ("entry.py" to
                """
                    from vendor.example import VALUE, DATA
                    from importlib.resources import files
                    from importlib import invalidate_caches
                    assert VALUE == 42
                    assert DATA == 'retained resource'
                    assert files('vendor.example').joinpath('sample.txt').read_text() == DATA
                    assert files('vendor').joinpath('example', 'sample.txt').read_text() == DATA
                    invalidate_caches()
                    assert files('vendor').joinpath('example', 'sample.txt').read_text() == DATA
                """.trimIndent())), dependencies = dependencies)
            assertFalse(inventory.modules.getValue("vendor.example").sleeved)
            assertEquals(Teleported.Bool(true), port.importInVm(inventory, "entry"))
            assertEquals("loaded", (port.blackboard().get("hermes/python/pointcut/import/vendor.example") as Map<*, *>)["status"])
        }
    }

    @Test fun modifiedMissingAndUnmanifestedWheelsFailBeforeExecution() {
        val root = wheel(mapOf("example.py" to "VALUE = 1"))
        val archive = root.resolve("lib/example-1.0-py3-none-any.whl")
        val original = Files.readAllBytes(archive)
        Files.write(archive, original.copyOf().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() })
        assertTrue(assertFailsWith<IllegalArgumentException> { PythonWheels.read(root) }.message.orEmpty().contains("sha256 drift"))
        Files.write(archive, original)
        Files.move(archive, archive.resolveSibling("other-1.0-py3-none-any.whl"))
        val failure = assertFailsWith<IllegalArgumentException> { PythonWheels.read(root) }.message.orEmpty()
        assertTrue(failure.contains("missing:"))
        assertTrue(failure.contains("unmanifested"))
    }

    @Test fun pathsNativeArtifactsAndDuplicateModulesAreRefused() {
        for (path in listOf("../outside.py", "/outside.py", "pkg/../../outside.py", "pkg\\evil.py", "x.pth", "_yaml.so", "a.data/purelib/a.py")) {
            assertFailsWith<IllegalArgumentException>(path) { PythonWheels.read(wheel(mapOf(path to "x"))) }
        }
        assertFailsWith<IllegalArgumentException> { PythonWheels.read(wheel(mapOf("x.py" to "", "x/__init__.py" to ""))) }
        assertFailsWith<IllegalArgumentException> { PythonWheels.read(wheel(mapOf("x.py" to ""), pure = false)) }
        val root = wheel(mapOf("x.py" to ""))
        val manifest = root.resolve("MANIFEST.tsv")
        val original = Files.readString(manifest)
        Files.writeString(manifest, original + original.lineSequence().first { it.startsWith("example-") } + "\n")
        assertFailsWith<IllegalArgumentException> { PythonWheels.read(root) }
    }

    @Test fun aWheelCannotGrantNativeReplacementPrivilegesOrShadowSource() {
        val dependencies = PythonWheels.read(wheel(mapOf("socket.py" to "VALUE = 1")))
        HermesPythonPort().use { port ->
            val inventory = port.inventorySources(mapOf("entry" to ("entry.py" to "import socket")), dependencies = dependencies)
            assertEquals(HermesModuleStatus.BLOCKED_NATIVE, inventory.modules.getValue("entry").status)
            assertFailsWith<IllegalArgumentException> { port.importInVm(inventory, "entry") }
            assertFailsWith<IllegalArgumentException> {
                port.inventorySources(mapOf("socket" to ("socket.py" to "VALUE = 2")), dependencies = dependencies)
            }
        }
    }

    @Test fun installedRuamelPreservesYaml11AndRoundTripEditingInTheGuest() {
        val dependencies = assertNotNull(PythonWheels.installed(), "run ./gradlew -p utils/subvm installHermesPython")
        assertTrue(dependencies.manifest.declared.contains("ruamel.yaml==0.18.16"))
        HermesPythonPort().use { port ->
            val inventory = port.inventorySources(mapOf("entry" to ("entry.py" to """
                from ruamel.yaml import YAML
                from ruamel.yaml.constructor import DuplicateKeyError
                from io import StringIO
                parser = YAML(typ='safe', pure=True)
                parser.version = (1, 1)
                values = parser.load('enabled: y\ndisabled: off\ncount: 42\n')
                assert values == {'enabled': True, 'disabled': False, 'count': 42}
                try:
                    parser.load('same: 1\nsame: 2\n')
                    raise AssertionError('duplicate key accepted')
                except DuplicateKeyError:
                    pass
                editor = YAML(typ='rt', pure=True)
                editor.preserve_quotes = True
                editor.width = 2**31 - 1
                original = '# retained\nlabel: "off" # inline\ncount: 1\n'
                document = editor.load(original)
                document['count'] = 2
                output = StringIO()
                editor.dump(document, output)
                assert output.getvalue() == original.replace('count: 1', 'count: 2'), output.getvalue()
                import sys
                assert '_ruamel_yaml' not in sys.modules
            """.trimIndent())), dependencies = dependencies)
            assertEquals(Teleported.Bool(true), port.importInVm(inventory, "entry"))
        }
    }

    @Test fun richLoadsItsVersionedUnicodeTablesInTheGuest() {
        val dependencies = assertNotNull(PythonWheels.installed())
        HermesPythonPort().use { port ->
            val inventory = port.inventorySources(mapOf("entry" to ("entry.py" to """
                from importlib import import_module
                from io import StringIO
                from rich.console import Console
                table = import_module('rich._unicode_data.unicode10-0-0')
                assert table.__name__ == 'rich._unicode_data.unicode10-0-0'
                output = StringIO()
                Console(file=output, color_system=None).print('[bold]Hermes[/bold]')
                assert output.getvalue() == 'Hermes\n'
            """.trimIndent())), dependencies = dependencies)
            assertEquals(Teleported.Bool(true), port.importInVm(inventory, "entry"))
        }
    }

    @Test fun installedDotenvParsesAndLoadsOnlyTheGuestEnvironment() {
        val dependencies = assertNotNull(PythonWheels.installed(), "run ./gradlew -p utils/subvm installHermesPython")
        val before = System.getenv("TRIKESHED_DOTENV_TEST_VALUE")
        HermesPythonPort().use { port ->
            val inventory = port.inventorySources(mapOf("entry" to ("entry.py" to """
                from dotenv import dotenv_values, load_dotenv
                from io import StringIO
                from pathlib import Path
                from importlib.metadata import version, distribution, distributions
                import os
                assert version('python-dotenv') == '1.2.2'
                assert version('ruamel.yaml') == '0.18.16'
                assert version('python_dotenv') == '1.2.2'
                assert distribution('python-dotenv').metadata['Name'] == 'python-dotenv'
                assert list(distributions(path=['/tmp'])) == []
                document = 'BASE=guest\nVALUE="${'$'}{BASE} value"\nexport EMPTY=\nBARE\n'
                values = dotenv_values(stream=StringIO(document))
                assert values == {'BASE': 'guest', 'VALUE': 'guest value', 'EMPTY': '', 'BARE': None}, values
                path = Path('/workspace/example.env')
                path.write_text('TRIKESHED_DOTENV_TEST_VALUE=guest-only\n')
                assert load_dotenv(path, override=True)
                assert os.environ['TRIKESHED_DOTENV_TEST_VALUE'] == 'guest-only'
            """.trimIndent())), dependencies = dependencies)
            assertEquals(Teleported.Bool(true), port.importInVm(inventory, "entry"))
        }
        assertEquals(before, System.getenv("TRIKESHED_DOTENV_TEST_VALUE"))
    }
}
