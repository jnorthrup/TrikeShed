package borg.trikeshed.hermes

/** Hermes ProviderProfile transport over the leased pen. No guest credentials or HTTP client. */
object HermesModelProvider {
    const val MODULE = "trikeshed_modelmux"

    val source = """
        import json
        from types import SimpleNamespace

        PROVIDER = 'trikeshed'
        BASE_URL = 'https://modelmux.invalid/v1'

        def _call(verb, *args):
            reply = json.loads(host.call(verb, *args))
            if reply.get('verdict') != 'ok':
                raise RuntimeError(str(reply.get('verdict')) + ': ' + str(reply.get('detail', '')))
            return reply

        def select_model(model=None, *, vision=False):
            catalog = _call('mux_catalog')
            selected = model or catalog.get('defaultModel')
            if not selected:
                raise ValueError('ModelMux has no default model; select a catalog id with :model ID')
            card = next((card for card in catalog['models'] if card['id'] == selected), None)
            if card is None:
                raise ValueError('ModelMux model is not in the current catalog: ' + str(selected))
            required = {'chat', 'vision'} if vision else {'chat'}
            if not required.issubset(card['capabilities']):
                raise ValueError('ModelMux model lacks ' + ', '.join(sorted(required)) + ': ' + selected)
            return selected

        def _transport_options(options):
            if options.get('explicit_api_key') or options.get('explicit_base_url') not in (None, '', BASE_URL):
                raise ValueError('ModelMux credentials and destination are host-owned')
            if options.get('api_mode') not in (None, '', 'chat_completions'):
                raise ValueError('ModelMux exposes chat_completions')
            if options.get('async_mode'):
                raise ValueError('ModelMux guest client is synchronous')

        def bind_runtime(runtime, auxiliary):
            # Extend only our provider in the donor's real resolvers. Keep their signatures,
            # foreign-provider behavior and config intact; no credentials or fake SDK modules.
            from functools import wraps
            from inspect import signature

            def bind(owner, name, provider_arg, model_arg, allowed, resolve):
                original = getattr(owner, name)
                if getattr(original, '_trikeshed_modelmux', False):
                    return original
                shape = signature(original)

                @wraps(original)
                def routed(*args, **kwargs):
                    bound = shape.bind(*args, **kwargs)
                    bound.apply_defaults()
                    options = bound.arguments
                    if (options.get(provider_arg) or '').strip().lower() != PROVIDER:
                        return original(*args, **kwargs)
                    if set(options) - allowed:
                        raise TypeError('Unsupported Hermes resolver arguments: ' + str(set(options) - allowed))
                    runtime._raise_if_provider_disabled(PROVIDER)
                    _transport_options(options)
                    model = select_model(options.get(model_arg), vision=bool(options.get('is_vision')))
                    return resolve(model)

                routed._trikeshed_modelmux = True
                return routed

            resolver = bind(runtime, 'resolve_runtime_provider', 'requested', 'target_model',
                            {'requested', 'target_model', 'explicit_api_key', 'explicit_base_url'},
                            lambda model: dict(provider=PROVIDER, requested_provider=PROVIDER,
                                               api_mode='chat_completions', api_key=None, base_url=BASE_URL,
                                               source='trikeshed-host-lease'))
            client = bind(auxiliary, 'resolve_provider_client', 'provider', 'model',
                          {'provider', 'model', 'async_mode', 'raw_codex', 'explicit_base_url',
                           'explicit_api_key', 'api_mode', 'main_runtime', 'is_vision', 'task'},
                          lambda model: (ModelMuxClient(), model))
            runtime.resolve_runtime_provider = resolver
            auxiliary.resolve_provider_client = client

        def run_oneshot(prompt, model=None):
            selected = select_model(model)
            from hermes_cli import runtime_provider
            from agent import auxiliary_client
            bind_runtime(runtime_provider, auxiliary_client)
            from hermes_cli.oneshot import run_oneshot as donor_run
            return donor_run(prompt, model=selected, provider=PROVIDER)

        class _Record(dict):
            def __getattr__(self, name):
                try:
                    return self[name]
                except KeyError:
                    raise AttributeError(name) from None

            def model_dump(self, *, mode='python', exclude_none=False, warnings=True):
                return _plain(self, exclude_none)

            def model_dump_json(self, **kwargs):
                return json.dumps(self.model_dump(**kwargs), ensure_ascii=False)

        def _plain(value, exclude_none=False):
            if isinstance(value, dict):
                return {key: _plain(item, exclude_none) for key, item in value.items()
                        if not exclude_none or item is not None}
            if isinstance(value, list):
                return [_plain(item, exclude_none) for item in value]
            return value

        def _record(value):
            if isinstance(value, dict):
                return _Record((key, _record(item)) for key, item in value.items())
            if isinstance(value, list):
                return [_record(item) for item in value]
            return value

        class _Stream:
            # ModelMux completes the request before this buffered, SDK-shaped view is returned.
            # No claim of incremental network streaming; usage and finish reasons are the provider's.
            buffered = True

            def __init__(self, completion):
                self.is_closed = False
                self.completion = completion

            def __getattr__(self, name):
                return getattr(self.completion, name)

            def __iter__(self):
                envelope = {key: value for key, value in self.completion.items()
                            if key not in ('choices', 'usage')}
                envelope['object'] = 'chat.completion.chunk'
                choices = []
                for index, choice in enumerate(self.completion.choices):
                    delta = dict(choice.message)
                    if delta.get('tool_calls') is not None:
                        delta['tool_calls'] = [dict(call, index=i) for i, call in enumerate(delta['tool_calls'])]
                    choices.append(dict(choice, index=choice.get('index', index), delta=delta))
                    choices[-1].pop('message', None)
                if not self.is_closed:
                    yield _record(dict(envelope, choices=choices, usage=None))
                if not self.is_closed and 'usage' in self.completion:
                    yield _record(dict(envelope, choices=[], usage=self.completion.usage))

            def close(self):
                self.is_closed = True

            def __enter__(self):
                return self

            def __exit__(self, *args):
                self.close()

        class ModelMuxClient:
            HERMES_SKIP_TRANSPORT_WRAP = True
            HERMES_HOST_TRANSPORT = True
            HERMES_HOST_TIMEOUT_SECONDS = ${HermesPen.MODEL_WALL_MILLIS / 1000}.0

            def __init__(self, **kwargs):
                # ProviderProfile may pass native-client options. They confer no host authority.
                self.is_closed = False
                self.api_key = None
                self.base_url = BASE_URL
                self._hermes_aux_effective_provider = PROVIDER
                self.chat = SimpleNamespace(completions=SimpleNamespace(create=self.create))

            def create(self, *, model, messages, stream=False, timeout=None, extra_body=None,
                       extra_headers=None, extra_query=None, stream_options=None, **kwargs):
                if self.is_closed:
                    raise RuntimeError('ModelMux client is closed')
                if extra_headers or extra_query:
                    raise ValueError('ModelMux transport headers and query are host-owned')
                if not isinstance(model, str) or not model.strip():
                    raise ValueError('ModelMux requires a catalog model id')
                if not isinstance(stream, bool):
                    raise ValueError('stream must be boolean')
                request = dict(kwargs, messages=messages)
                if extra_body is not None:
                    if not isinstance(extra_body, dict):
                        raise ValueError('extra_body must be an object')
                    if set(extra_body).intersection(request) or set(extra_body).intersection(('model', 'stream')):
                        raise ValueError('extra_body cannot replace completion fields')
                    request.update(extra_body)
                if stream_options is not None and stream_options != {'include_usage': True}:
                    raise ValueError('only include_usage is supported for buffered chunks')
                if timeout is not None and (isinstance(timeout, bool) or not isinstance(timeout, (int, float))
                                            or not 0 < timeout <= 2147483):
                    raise ValueError('timeout must be positive seconds')
                timeout = self.HERMES_HOST_TIMEOUT_SECONDS if timeout is None else min(timeout, self.HERMES_HOST_TIMEOUT_SECONDS)
                args = (model, json.dumps(request, ensure_ascii=False, allow_nan=False))
                args += (max(1, int(timeout * 1000)),)
                completion = _record(_call('mux_completion', *args)['completion'])
                return _Stream(completion) if stream else completion

            def close(self):
                self.is_closed = True

            def __enter__(self):
                if self.is_closed:
                    raise RuntimeError('ModelMux client is closed')
                return self

            def __exit__(self, *args):
                self.close()

        def register():
            # Import the donor's actual registry/base. Nothing replaces the OpenAI or Hermes packages.
            from providers import register_provider
            from providers.base import ProviderProfile

            class ModelMuxProfile(ProviderProfile):
                def create_client(self, **kwargs):
                    return ModelMuxClient(**kwargs)

                def fetch_models(self, **kwargs):
                    return [model['id'] for model in _call('mux_catalog')['models']]

            profile = ModelMuxProfile(name=PROVIDER, api_mode='chat_completions',
                                      base_url=BASE_URL, env_vars=(),
                                      supports_health_check=False, supports_model_listing=True)
            register_provider(profile)
            return profile
    """.trimIndent()
}
