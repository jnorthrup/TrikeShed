"""Hermes provider registration over TrikeShed's owned ModelMux crossing.

Python, HTTPX, OpenAI objects, Hermes sessions and tools remain stock. ModelMux
owns provider credentials and network destinations; this transport forwards only
the completion body. Its SSE response is explicitly buffered, not network streaming.
"""

import json

import httpx
from openai import OpenAI

PROVIDER = "trikeshed"
BASE_URL = "https://modelmux.invalid/v1"
TIMEOUT_SECONDS = 300.0
_host_call = None


def install(call):
    global _host_call
    if not callable(call):
        raise TypeError("ModelMux requires an owned host call")
    if _host_call is not None and _host_call is not call:
        raise RuntimeError("ModelMux host call is already installed")
    _host_call = call


def _call(verb, *args):
    if _host_call is None:
        raise RuntimeError("ModelMux host call is not installed")
    reply = json.loads(_host_call(verb, *args))
    if reply.get("verdict") != "ok":
        raise RuntimeError(f"{reply.get('verdict')}: {reply.get('detail', '')}")
    return reply


def select_model(model=None, *, vision=False):
    catalog = _call("mux_catalog")
    selected = model or catalog.get("defaultModel")
    card = next((card for card in catalog["models"] if card["id"] == selected), None)
    if card is None:
        raise ValueError(f"ModelMux model is not in the current catalog: {selected}")
    required = {"chat", "vision"} if vision else {"chat"}
    if not required.issubset(card["capabilities"]):
        raise ValueError(f"ModelMux model lacks {', '.join(sorted(required))}: {selected}")
    return selected


def _sse(completion):
    envelope = {key: value for key, value in completion.items() if key not in ("choices", "usage")}
    envelope["object"] = "chat.completion.chunk"
    choices = []
    for index, choice in enumerate(completion["choices"]):
        chunk = dict(choice)
        delta = dict(chunk.pop("message"))
        if delta.get("tool_calls") is not None:
            delta["tool_calls"] = [dict(call, index=i) for i, call in enumerate(delta["tool_calls"])]
        chunk.update(index=choice.get("index", index), delta=delta)
        choices.append(chunk)
    chunks = [dict(envelope, choices=choices, usage=None)]
    if completion.get("usage") is not None:
        chunks.append(dict(envelope, choices=[], usage=completion["usage"]))
    return ("".join("data: " + json.dumps(chunk, ensure_ascii=False, allow_nan=False) + "\n\n"
                    for chunk in chunks) + "data: [DONE]\n\n").encode("utf-8")


class ModelMuxTransport(httpx.BaseTransport):
    def handle_request(self, request):
        if str(request.url.copy_with(path="/", query=None)) != "https://modelmux.invalid/":
            raise httpx.UnsupportedProtocol("ModelMux destinations are host-owned", request=request)
        if request.method != "POST" or request.url.path != "/v1/chat/completions" or request.url.query:
            raise httpx.UnsupportedProtocol("ModelMux supports chat completions", request=request)
        body = json.loads(request.read())
        model = body.pop("model")
        stream = body.pop("stream", False)
        stream_options = body.pop("stream_options", None)
        if stream_options not in (None, {"include_usage": True}):
            raise ValueError("ModelMux buffered SSE supports include_usage only")
        timeout = request.extensions.get("timeout", {}).get("read")
        timeout = TIMEOUT_SECONDS if timeout is None else min(float(timeout), TIMEOUT_SECONDS)
        if not 0 < timeout <= TIMEOUT_SECONDS:
            raise ValueError("ModelMux timeout must be positive seconds")
        completion = _call("mux_completion", model,
                           json.dumps(body, ensure_ascii=False, allow_nan=False),
                           max(1, int(timeout * 1000)))["completion"]
        if stream:
            return httpx.Response(200, headers={"content-type": "text/event-stream", "x-trikeshed-buffered": "true"},
                                  content=_sse(completion))
        return httpx.Response(200, json=completion)


def create_client(**options):
    # The transport owns no network client, endpoint credentials or shared pool.
    # OpenAI.close() owns and closes this HTTPX client and its transport normally.
    if str(options.get("base_url") or BASE_URL).rstrip("/") != BASE_URL:
        raise ValueError("ModelMux destinations are host-owned")
    client = OpenAI(api_key="", base_url=BASE_URL, max_retries=0,
                    timeout=TIMEOUT_SECONDS if options.get("timeout") is None else options["timeout"],
                    http_client=httpx.Client(transport=ModelMuxTransport(), trust_env=False))
    client._hermes_aux_effective_provider = PROVIDER
    return client


def register():
    from providers import register_provider
    from providers.base import ProviderProfile

    class ModelMuxProfile(ProviderProfile):
        def create_client(self, **options):
            return create_client(**options)

        def fetch_models(self, **options):
            return [card["id"] for card in _call("mux_catalog")["models"]]

    profile = ModelMuxProfile(name=PROVIDER, api_mode="chat_completions", base_url=BASE_URL,
                              supports_health_check=False)
    register_provider(profile)
    return profile


def bind_runtime(runtime, auxiliary):
    from functools import wraps
    from inspect import signature

    original = runtime.resolve_runtime_provider
    if not getattr(original, "_trikeshed_modelmux", False):
        @wraps(original)
        def resolve(*, requested=None, explicit_api_key=None, explicit_base_url=None, target_model=None):
            if (requested or "").strip().lower() != PROVIDER:
                return original(requested=requested, explicit_api_key=explicit_api_key,
                                explicit_base_url=explicit_base_url, target_model=target_model)
            runtime._raise_if_provider_disabled(PROVIDER)
            if explicit_api_key not in (None, "", "no-key-required") or explicit_base_url not in (None, "", BASE_URL):
                raise ValueError("ModelMux credentials and destinations are host-owned")
            select_model(target_model)
            return dict(provider=PROVIDER, requested_provider=PROVIDER, api_mode="chat_completions",
                        api_key=None, base_url=BASE_URL, source="trikeshed-host-lease")
        resolve._trikeshed_modelmux = True
        runtime.resolve_runtime_provider = resolve

    original_aux = auxiliary.resolve_provider_client
    if not getattr(original_aux, "_trikeshed_modelmux", False):
        shape = signature(original_aux)

        @wraps(original_aux)
        def resolve_aux(*args, **kwargs):
            bound = shape.bind(*args, **kwargs)
            bound.apply_defaults()
            options = bound.arguments
            if (options.get("provider") or "").strip().lower() != PROVIDER:
                return original_aux(*args, **kwargs)
            if options.get("async_mode") or options.get("raw_codex"):
                raise ValueError("ModelMux auxiliary bridge supports synchronous chat completions")
            if options.get("api_mode") not in (None, "", "chat_completions"):
                raise ValueError("ModelMux exposes chat completions")
            runtime._raise_if_provider_disabled(PROVIDER)
            if options.get("explicit_api_key") not in (None, "", "no-key-required") or options.get("explicit_base_url") not in (None, "", BASE_URL):
                raise ValueError("ModelMux credentials and destinations are host-owned")
            model = select_model(options.get("model"), vision=bool(options.get("is_vision")))
            return create_client(), model
        resolve_aux._trikeshed_modelmux = True
        auxiliary.resolve_provider_client = resolve_aux
