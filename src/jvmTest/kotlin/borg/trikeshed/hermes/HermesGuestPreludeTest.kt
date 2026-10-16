package borg.trikeshed.hermes

import borg.trikeshed.graal.ConfixBlackboard
import borg.trikeshed.vm.Teleported
import kotlinx.coroutines.*
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

class HermesGuestPreludeTest {
    @TempDir lateinit var directory: Path

    @Test
    fun cliCommandExitFollowsItsCausalReceipt() {
        val cli = Files.createDirectories(directory.resolve("hermes_cli"))
        Files.writeString(cli.resolve("__init__.py"), "")
        Files.writeString(cli.resolve("main.py"), "READY = True")
        val failure = assertFailsWith<IllegalStateException> {
            HermesPythonPortCli.main(arrayOf("--root", directory.toString(), "--sleeve", directory.resolve("empty-sleeve").toString(), "--command", ":eval raise RuntimeError('expected refusal')"))
        }
        assertTrue(failure.message.orEmpty().contains("command failed"))
        HermesPythonPortCli.main(arrayOf("--root", directory.toString(), "--sleeve", directory.resolve("empty-sleeve").toString(), "--command", ":eval 6 * 7"))
    }

    @Test
    fun cliDoesNotReportSuccessWhenTheConsoleCannotBoot() {
        val failure = assertFailsWith<IllegalStateException> {
            HermesPythonPortCli.main(arrayOf("--root", "/__trikeshed_missing_hermes_fixture__", "--command", ":help"))
        }
        assertTrue(failure.message.orEmpty().contains("Hermes source root missing"))
    }

    private fun verify(source: String) = runBlocking(Dispatchers.IO) {
        val board = ConfixBlackboard.empty()
        HermesPythonPort(blackboard = board, pen = HermesPen(board), socketScope = this).use { port ->
            val inventory = port.inventorySources(mapOf("entry" to ("entry.py" to source)))
            assertEquals(Teleported.Bool(true), port.importInVm(inventory, "entry"))
            assertTrue(port.vmStarted)
        }
    }

    @Test
    fun entryArgumentsNameTheVirtualModuleAndPreserveSuppliedArguments() = runBlocking(Dispatchers.IO) {
        for ((entry, relativePath) in listOf("entry" to "entry.py", "package_entry" to "package_entry/__init__.py")) {
            val board = ConfixBlackboard.empty()
            HermesPythonPort(blackboard = board, pen = HermesPen(board), install = { guest ->
                guest.eval("import sys\nsys.argv = []\nTrue", "empty-arguments.py")
            }).use { port ->
                val source = "import sys\nassert sys.argv == ['/workspace/$relativePath'], sys.argv"
                assertEquals(Teleported.Bool(true), port.importInVm(port.inventorySources(mapOf(entry to (relativePath to source))), entry))
            }
        }
        HermesPythonPort(install = { guest ->
            guest.eval("import sys\nsys.argv = ['caller.py', '--version']\nTrue", "supplied-arguments.py")
        }).use { port ->
            val source = "import sys\nassert sys.argv == ['caller.py', '--version'], sys.argv"
            assertEquals(Teleported.Bool(true), port.importInVm(port.inventorySources(mapOf("entry" to ("entry.py" to source))), "entry"))
        }
    }

    @Test
    fun contextVariablesRetainNamesTokensMappingsAndNativeIsolation() = verify("""
        from contextvars import ContextVar, Context, Token, copy_context
        from types import GenericAlias
        for cls in (ContextVar, Token):
            alias = cls[str]
            assert isinstance(alias, GenericAlias)
            assert alias.__origin__ is cls and alias.__args__ == (str,)
        def typed_set(variable: ContextVar[str]) -> Token[str]:
            return variable.set('typed')
        assert typed_set.__annotations__['return'] == Token[str]
        typed = ContextVar[str]('typed')
        typed_token = typed_set(typed)
        assert typed_token.var is typed and typed.get() == 'typed'
        typed.reset(typed_token)
        var = ContextVar('session', default='default')
        assert var.name == 'session' and var.get() == 'default'
        try: var.name = 'other'
        except AttributeError: pass
        else: raise AssertionError('name is mutable')
        token = var.set('outer')
        assert token.var is var and token.old_value is Token.MISSING
        context = copy_context()
        assert context[var] == 'outer' and var in context
        assert list(context) == [var] and dict(context.items()) == {var: 'outer'}
        with var.set('inner') as changed:
            assert changed.old_value == 'outer' and var.get() == 'inner'
            assert context.run(var.get) == 'outer'
        assert var.get() == 'outer'
        context.run(var.set, 'copy')
        assert context.get(var) == 'copy' and var.get() == 'outer'
        duplicate = context.copy()
        assert duplicate[var] == 'copy'
        assert len(context) == 1 and len(Context()) == 0
        assert Context().get(var) is None
        other = ContextVar('other')
        try: other.reset(token)
        except ValueError: pass
        else: raise AssertionError('wrong variable accepted token')
        try: context.run(var.reset, token)
        except ValueError: pass
        else: raise AssertionError('wrong context accepted token')
        assert var.get() == 'outer' and context[var] == 'copy'
        copied_token = context.run(var.set, 'temporary')
        try: duplicate.run(var.reset, copied_token)
        except ValueError: pass
        else: raise AssertionError('copied context accepted token')
        context.run(var.reset, copied_token)
        assert context[var] == 'copy' and duplicate[var] == 'copy'
        def fail_in_context():
            assert var.get() == 'copy'
            raise RuntimeError('context exit')
        try: context.run(fail_in_context)
        except RuntimeError: pass
        else: raise AssertionError('context swallowed exception')
        assert var.get() == 'outer'
        var.reset(token)
        assert var.get() == 'default'
        try: var.reset(token)
        except RuntimeError: pass
        else: raise AssertionError('token reused')
    """.trimIndent())

    @Test
    fun standardTlsImportsDoNotGrantNonblockingTransport() = verify("""
        import asyncio, ssl
        for kind in (ssl.SSLZeroReturnError, ssl.SSLWantReadError, ssl.SSLWantWriteError,
                     ssl.SSLSyscallError, ssl.SSLEOFError, ssl.SSLCertVerificationError):
            assert issubclass(kind, ssl.SSLError)
        context = ssl.create_default_context()
        try: context.wrap_bio(None, None)
        except OSError as failure: assert failure.errno == 95
        else: raise AssertionError('unleased BIO transport admitted')
    """.trimIndent())

    @Test
    fun threadRefusalIsCatchableWithoutAnExternalSleeve() = verify("""
        import threading
        try:
            threading.Thread(target=lambda: None).start()
        except RuntimeError as failure:
            assert str(failure) == "can't start new thread", failure
        else:
            raise AssertionError('guest thread was admitted')
    """.trimIndent())

    @Test
    fun loggingDeliversLevelsAndDrainsQueuedRecordsAcrossRestart() = verify("""
        import logging, queue
        from logging.handlers import QueueHandler, QueueListener
        seen = []
        class Capture(logging.Handler):
            def emit(self, record): seen.append(record.getMessage())
        q = queue.Queue()
        capture = Capture(logging.WARNING)
        listener = QueueListener(q, capture, respect_handler_level=True)
        sender = QueueHandler(q)
        def record(text, level=logging.ERROR):
            return logging.LogRecord('probe', level, 'probe', 1, text, (), None)
        sender.handle(record('before start'))
        listener.start()
        assert seen == ['before start'], seen
        sender.handle(record('filtered', logging.INFO))
        sender.handle(record('running'))
        assert seen == ['before start', 'running'], seen
        listener.stop()
        sender.handle(record('after stop'))
        assert q.qsize() == 1
        listener.start()
        assert seen == ['before start', 'running', 'after stop'], seen
        q.put(record('direct queue'))
        listener.stop()
        assert seen[-1] == 'direct queue', seen
        assert q.qsize() == 0 and q.unfinished_tasks == 0
    """.trimIndent())

    @Test
    fun loggingReentryAndSimpleQueueDoNotLoseRecords() = verify("""
        import logging, queue
        from logging.handlers import QueueHandler, QueueListener
        seen = []
        q = queue.SimpleQueue()
        sender = QueueHandler(q)
        class Capture(logging.Handler):
            def emit(self, record):
                seen.append(record.getMessage())
                if record.msg == 'outer':
                    sender.handle(logging.LogRecord('probe', logging.ERROR, 'probe', 1, 'inner', (), None))
        listener = QueueListener(q, Capture())
        listener.start()
        sender.handle(logging.LogRecord('probe', logging.ERROR, 'probe', 1, 'outer', (), None))
        listener.stop()
        assert seen == ['outer', 'inner'], seen
        assert q.qsize() == 0
    """.trimIndent())
}
