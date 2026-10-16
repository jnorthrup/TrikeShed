package borg.trikeshed.graal.subvm

import borg.trikeshed.pointcut.VmFacet
import borg.trikeshed.vm.Teleported
import kotlin.test.*

class GraalPythonNativeModuleTest {
    fun python() = GraalBtrfsSupervisor("native-${System.nanoTime()}", VmFacet.GRAAL_PYTHON)

    fun nativeImport(isolate: GuestIsolate) {
        assertEquals(Teleported.Num(42), isolate.eval("""
            try:
                import termios
            except ImportError:
                pass
            else:
                raise AssertionError('native termios module was admitted')
            import json
            json.loads('{"answer": 42}')['answer']
        """.trimIndent()))
        val failure = assertFailsWith<GuestException> { isolate.eval("import termios") }
        assertEquals(GuestFailure.GUEST_ERROR, failure.kind)
        assertTrue(isolate.isAlive)
        assertEquals(Teleported.Num(42), isolate.eval("6 * 7"))
    }

    @Test
    fun nativeImportsRemainGuestErrorsAcrossLiveAndRecreatedContexts() {
        python().use { first ->
            nativeImport(first)
            python().use { second ->
                nativeImport(second)
                nativeImport(first)
            }
        }
        python().use(::nativeImport)
    }

    @Test
    fun nativeLoaderRefusesBeforeNativeApiInitialization() {
        python().use { isolate ->
            assertEquals(Teleported.Num(42), isolate.eval("""
                import _imp
                from importlib.machinery import ModuleSpec
                try:
                    _imp.create_dynamic(ModuleSpec('native_probe', None, origin='/workspace/native_probe.so'))
                except ImportError as failure:
                    assert 'native module withheld' in str(failure), failure
                else:
                    raise AssertionError('native loader was admitted')
                6 * 7
            """.trimIndent()))
            assertTrue(isolate.isAlive)
        }
    }

    @Test
    fun leafNativeLoaderRefusalInitializesOnTheLeafWorker() {
        python().use { parent ->
            parent.delegate("leaf") {
                LeafTrainer.LeafHost(GuestBounds.PYTHON, parent.id, Budget()).use { leaf ->
                    leaf.materialize("native_import", """
                        def native_import():
                            import _imp
                            from importlib.machinery import ModuleSpec
                            try:
                                _imp.create_dynamic(ModuleSpec('native_probe', None, origin='/workspace/native_probe.so'))
                            except ImportError as failure:
                                assert 'native module withheld' in str(failure), failure
                            else:
                                raise AssertionError('leaf native loader was admitted')
                            return 42
                    """.trimIndent())
                    leaf.call("native_import", Teleported.Arr(emptyList()))
                }
            }
            assertEquals(Teleported.Num(42), parent.eval("host.call('leaf')"))
            assertTrue(parent.isAlive)
            assertEquals(Teleported.Num(42), parent.eval("6 * 7"))
        }
    }
}
