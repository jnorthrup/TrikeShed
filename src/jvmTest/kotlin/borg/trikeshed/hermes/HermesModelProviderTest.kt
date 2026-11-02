package borg.trikeshed.hermes

import borg.trikeshed.graal.ConfixBlackboard
import borg.trikeshed.vm.Teleported
import borg.trikeshed.agent.keymux.KeyMux
import borg.trikeshed.agent.modelmux.ModelMux
import borg.trikeshed.lib.*
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals

class HermesModelProviderTest {
    @Test
    fun providerContextLengthTracksKnownHostCatalogMetadata() {
        val board = ConfixBlackboard.empty()
        val lease = AtomicReference<ModelMux?>(ModelMux(KeyMux {}) {
            model("known", contextWindow = 131072, caps = _s["chat"])
            model("unknown", caps = _s["chat"])
            model("invalid", contextWindow = -1, caps = _s["chat"])
            defaultModel("known")
        })
        // Registry/base contracts are fixtures; GraalPy, provider source, host catalog and
        // lease are production code. These capacities are synthetic catalog metadata.
        val profiles = _m[
            "providers" j ("providers/__init__.py" j """
                profiles = {}
                def register_provider(profile): profiles[profile.name] = profile
            """.trimIndent()),
            "providers.base" j ("providers/base.py" j """
                class ProviderProfile:
                    def __init__(self, **kwargs): self.__dict__.update(kwargs)
                    def get_model_context_length(self, model): return None
            """.trimIndent()),
        ].mapValues { (_, entry) -> entry.pair }
        HermesPythonPort(blackboard = board, pen = HermesPen(board, modelMuxProvider = lease::get)).use { port ->
            port.importInVm(port.inventorySources(profiles), HermesModelProvider.MODULE)
            assertEquals(Teleported.Bool(true), port.evalInVm("""
                import trikeshed_modelmux as mux
                profile = mux.register()
                assert profile.get_model_context_length('known') == 131072
                assert profile.get_model_context_length('unknown') is None
                assert profile.get_model_context_length('invalid') is None
                assert profile.get_model_context_length('missing') is None
                assert profile.get_model_context_length(None) is None
                True
            """.trimIndent()))
            lease.set(ModelMux(KeyMux {}) {
                model("known", contextWindow = 65536, caps = _s["chat"])
            })
            assertEquals(Teleported.Bool(true), port.evalInVm("""
                assert profile.get_model_context_length('known') == 65536
                assert profile.get_model_context_length('unknown') is None
                True
            """.trimIndent()))
            lease.set(null)
            assertEquals(Teleported.Bool(true), port.evalInVm("""
                try:
                    profile.get_model_context_length('known')
                except RuntimeError as error:
                    assert 'no-mux' in str(error)
                else:
                    raise AssertionError('revoked metadata lease admitted')
                True
            """.trimIndent()))
        }
    }

    @Test
    fun leasedProviderResolvesWithoutGuestCredentialsAndRetainsForeignResolvers() {
        val board = ConfixBlackboard.empty()
        val lease = AtomicReference<ModelMux?>(ModelMux(KeyMux {}) {
            model("text", caps = setOf("chat"))
            model("image", caps = setOf("chat", "vision"))
            model("embedding", caps = setOf("embedding"))
            defaultModel("text")
        })
        HermesPythonPort(blackboard = board, pen = HermesPen(board, modelMuxProvider = lease::get)).use { port ->
            port.importInVm(port.inventorySources(resolverSources), HermesModelProvider.MODULE)
            assertEquals(Teleported.Bool(true), port.evalInVm("""
                import trikeshed_modelmux as mux
                from hermes_cli import runtime_provider as runtime
                from agent import auxiliary_client as auxiliary
                original_runtime = runtime.resolve_runtime_provider
                original_client = auxiliary.resolve_provider_client
                mux.bind_runtime(runtime, auxiliary)
                bound_runtime = runtime.resolve_runtime_provider
                bound_client = auxiliary.resolve_provider_client
                mux.bind_runtime(runtime, auxiliary)
                assert bound_runtime is runtime.resolve_runtime_provider
                assert bound_client is auxiliary.resolve_provider_client
                assert mux.select_model() == 'text'
                assert mux.select_model('image', vision=True) == 'image'
                resolved = runtime.resolve_runtime_provider(requested=' TRIKESHED ', target_model='text')
                assert resolved == dict(provider='trikeshed', requested_provider='trikeshed', api_mode='chat_completions',
                                       api_key=None, base_url=mux.BASE_URL, source='trikeshed-host-lease')
                client, model = auxiliary.resolve_provider_client('trikeshed', 'image', is_vision=True)
                assert model == 'image' and isinstance(client, mux.ModelMuxClient)
                assert client.api_key is None and client._hermes_aux_effective_provider == 'trikeshed'
                assert runtime.resolve_runtime_provider(requested='foreign', explicit_api_key='fixture') == original_runtime(
                    requested='foreign', explicit_api_key='fixture')
                assert auxiliary.resolve_provider_client('foreign', 'model', True, task='fixture') == original_client(
                    'foreign', 'model', True, task='fixture')
                for invalid in (dict(model='missing'), dict(model='embedding'), dict(model='text', is_vision=True),
                                dict(explicit_api_key='guest'), dict(explicit_base_url='https://foreign.invalid'),
                                dict(api_mode='codex_responses'), dict(async_mode=True)):
                    try:
                        auxiliary.resolve_provider_client('trikeshed', **invalid)
                    except ValueError:
                        pass
                    else:
                        raise AssertionError('invalid route admitted: ' + str(invalid))
                runtime.disabled = True
                try:
                    runtime.resolve_runtime_provider(requested='trikeshed')
                except ValueError as error:
                    assert str(error) == 'provider disabled'
                else:
                    raise AssertionError('disabled provider admitted')
                try:
                    auxiliary.resolve_provider_client('trikeshed')
                except ValueError as error:
                    assert str(error) == 'provider disabled'
                else:
                    raise AssertionError('disabled auxiliary provider admitted')
                runtime.disabled = False
                True
            """.trimIndent()))
            lease.set(ModelMux(KeyMux {}) {
                model("replacement", caps = setOf("chat"))
                defaultModel("replacement")
            })
            assertEquals(Teleported.Bool(true), port.evalInVm("""
                assert mux.select_model() == 'replacement'
                assert auxiliary.resolve_provider_client('trikeshed')[1] == 'replacement'
                try:
                    runtime.resolve_runtime_provider(requested='trikeshed', target_model='text')
                except ValueError:
                    pass
                else:
                    raise AssertionError('stale selection silently changed model')
                True
            """.trimIndent()))
            lease.set(ModelMux(KeyMux {}) { model("unselected", caps = setOf("chat")) })
            assertEquals(Teleported.Bool(true), port.evalInVm("""
                try:
                    mux.select_model()
                except ValueError as error:
                    assert 'no default' in str(error)
                else:
                    raise AssertionError('unconfigured default silently chosen')
                assert mux.select_model('unselected') == 'unselected'
                True
            """.trimIndent()))
            lease.set(null)
            assertEquals(Teleported.Bool(true), port.evalInVm("""
                try:
                    auxiliary.resolve_provider_client('trikeshed', 'unselected')
                except RuntimeError as error:
                    assert 'no-mux' in str(error)
                else:
                    raise AssertionError('revoked lease admitted')
                True
            """.trimIndent()))
        }
    }

    companion object {
        // Only the resolver contracts are fixtures. The injected transport, GraalPy, lease and
        // host crossings are production code; this is not a full donor-agent bootstrap proof.
        val resolverSources = mapOf(
            "hermes_cli" to ("hermes_cli/__init__.py" to ""),
            "hermes_cli.runtime_provider" to ("hermes_cli/runtime_provider.py" to """
                disabled = False
                def _raise_if_provider_disabled(provider):
                    if disabled: raise ValueError('provider disabled')
                def resolve_runtime_provider(*, requested=None, explicit_api_key=None, explicit_base_url=None, target_model=None):
                    return dict(provider=requested, api_key=explicit_api_key, base_url=explicit_base_url, model=target_model)
            """.trimIndent()),
            "agent" to ("agent/__init__.py" to ""),
            "agent.auxiliary_client" to ("agent/auxiliary_client.py" to """
                def resolve_provider_client(provider, model=None, async_mode=False, raw_codex=False,
                                            explicit_base_url=None, explicit_api_key=None, api_mode=None,
                                            main_runtime=None, is_vision=False, task=None):
                    return dict(provider=provider, async_mode=async_mode, raw_codex=raw_codex,
                                base_url=explicit_base_url, api_key=explicit_api_key, api_mode=api_mode,
                                main_runtime=main_runtime, is_vision=is_vision, task=task), model
            """.trimIndent()),
        )
    }
}
