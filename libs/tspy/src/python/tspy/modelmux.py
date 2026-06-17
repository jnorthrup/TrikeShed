"""tspy.modelmux — lazy model listing cache and provider router.

Kernel-algebra port of libs/modelmux. Builds on tspy.keymux.

ModelMux caches /v1/models listings per provider lazily (only fetched when
first asked, and only for providers with loaded keys). The cache is a
MetaSeries<provider, Series<model_id>> — provider -> its models.

No HTTP happens at import time. Listings are fetched on demand via
urllib (stdlib only, zero dependencies) and cached in-memory.
"""

from __future__ import annotations
from typing import Any, Callable, Iterator
from dataclasses import dataclass, field
import json
import threading
import urllib.request
import urllib.error

from tspy.algebra import Join, j, Series, MetaSeries, s_
from tspy.keymux import KeyEntry, KeyStore, get_store, PROVIDERS, _PROVIDER_INDEX, ProviderSpec


# ---------------------------------------------------------------------------
# ModelEntry — Join<provider, model_id>
# ---------------------------------------------------------------------------

@dataclass(frozen=True, slots=True)
class ModelEntry:
    """A model listing entry: provider j model_id."""
    provider: str
    model_id: str
    owned_by: str = ""

    def pair(self) -> Join[str, str]:
        return j(self.provider, self.model_id)

    def __str__(self) -> str:
        return f"{self.provider}/{self.model_id}"


# ---------------------------------------------------------------------------
# Listing fetcher — stdlib urllib, OpenAI-compatible /v1/models
# ---------------------------------------------------------------------------

def _fetch_models(base_url: str, api_key: str, timeout: float = 8.0) -> tuple[str, ...]:
    """Fetch model ids from a provider's /v1/models endpoint.

    Uses a short timeout and swallows all errors — returns () on any failure
    so the caller never blocks.
    """
    url = base_url.rstrip("/")
    if not url.endswith("/v1/models"):
        if url.endswith("/v1"):
            url = url + "/models"
        elif url.endswith("/v4"):
            url = url + "/models"
        else:
            url = url + "/v1/models"
    req = urllib.request.Request(url, headers={
        "Authorization": f"Bearer {api_key}",
        "Accept": "application/json",
    })
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            data = json.loads(resp.read())
    except (urllib.error.URLError, OSError, json.JSONDecodeError, TimeoutError):
        return ()
    # OpenAI shape: {"data": [{"id": "...", "owned_by": "..."}, ...]}
    ids = []
    if isinstance(data, dict) and "data" in data:
        for item in data["data"]:
            mid = item.get("id") if isinstance(item, dict) else None
            if mid:
                ids.append(mid)
    elif isinstance(data, list):
        for item in data:
            mid = item.get("id") if isinstance(item, dict) else None
            if mid:
                ids.append(mid)
    return tuple(ids)


# ---------------------------------------------------------------------------
# ModelMux — lazy listing cache + routing
# ---------------------------------------------------------------------------

class ModelMux:
    """
    Lazy model listing cache. Does not fetch anything until asked.

    Usage:
        mux = ModelMux()                # uses default KeyStore (env scan)
        mux.refresh()                   # fetch listings for all providers with keys
        models = mux.models("deepseek") # cached
        entry = mux.pick("deepseek/deepseek-chat")
    """

    def __init__(
        self,
        store: KeyStore | None = None,
        fetcher: Callable[[str, str], tuple[str, ...]] | None = None,
    ):
        self._store = store or get_store()
        self._fetcher = fetcher or _fetch_models
        self._cache: dict[str, tuple[str, ...]] = {}
        self._lock = threading.Lock()
        self._refreshed_all = False

    @property
    def store(self) -> KeyStore:
        return self._store

    # --- Per-provider lazy listing -----------------------------------------

    def models(self, provider: str, force: bool = False) -> tuple[str, ...]:
        """Get cached model ids for a provider; fetch lazily if missing."""
        if not force and provider in self._cache:
            return self._cache[provider]
        key = self._store.get(provider)
        if key is None:
            return ()
        with self._lock:
            if not force and provider in self._cache:
                return self._cache[provider]
            ids = self._fetcher(key.base_url, key.secret)
            self._cache[provider] = ids
            return ids

    def has_model(self, model: str) -> bool:
        """Check if a model id (provider/model or plain) exists in cache."""
        if "/" in model:
            prov, mid = model.split("/", 1)
            return mid in self.models(prov)
        # plain — check all providers
        for prov in self._store.providers():
            if model in self.models(prov):
                return True
        return False

    # --- Bulk refresh ------------------------------------------------------

    def refresh(self, timeout_per: float = 8.0) -> dict[str, int]:
        """Fetch listings for all providers with loaded keys concurrently.

        Uses a ThreadPoolExecutor (stdlib concurrent.futures). Each provider
        fetch runs in parallel with its own timeout. Never blocks on a single
        slow provider — all complete or time out within ~timeout_per seconds.
        """
        import concurrent.futures

        providers = self._store.providers()
        counts: dict[str, int] = {}

        def _fetch_one(prov: str) -> tuple[str, tuple[str, ...]]:
            key = self._store.get(prov)
            if key is None:
                return (prov, ())
            try:
                ids = self._fetcher(key.base_url, key.secret, timeout_per)
            except Exception:
                ids = ()
            return (prov, ids)

        with concurrent.futures.ThreadPoolExecutor(max_workers=min(len(providers), 16)) as pool:
            futures = {pool.submit(_fetch_one, prov): prov for prov in providers}
            try:
                for future in concurrent.futures.as_completed(futures, timeout=timeout_per + 2):
                    prov = futures[future]
                    try:
                        _, ids = future.result()
                    except Exception:
                        ids = ()
                    with self._lock:
                        self._cache[prov] = ids
                    counts[prov] = len(ids)
            except TimeoutError:
                # Don't crash — collect whichever providers finished
                for future, prov in futures.items():
                    if prov not in counts:
                        if future.done():
                            try:
                                _, ids = future.result()
                            except Exception:
                                ids = ()
                        else:
                            ids = ()
                        with self._lock:
                            self._cache[prov] = ids
                        counts[prov] = len(ids)

        self._refreshed_all = True
        return counts

    # --- Series views ------------------------------------------------------

    def listing_series(self) -> Series[Series[ModelEntry]]:
        """Return a Series (one slot per cached provider) where each slot is
        itself a Series[ModelEntry] for that provider's models."""
        providers = self._store.providers()
        cached = tuple(p for p in providers if p in self._cache)

        def make_provider_series(prov: str) -> Series[ModelEntry]:
            ids = self._cache.get(prov, ())
            return Series(len(ids), lambda i: ModelEntry(prov, ids[i]))

        entries = tuple(make_provider_series(p) for p in cached)
        return Series.from_tuple(entries)

    def all_models(self) -> Series[ModelEntry]:
        """Flattened Series of all cached ModelEntry across providers."""
        entries: list[ModelEntry] = []
        for prov in self._store.providers():
            for mid in self.models(prov):
                entries.append(ModelEntry(prov, mid))
        return Series.from_tuple(tuple(entries))

    # --- Routing / selection -----------------------------------------------

    def pick(self, model: str, tokens: int = 1000) -> ModelEntry | None:
        """
        Route a model id to a ModelEntry with an available, quota-bearing key.

        Accepts "provider/model" or plain "model" (searches all providers).
        Returns None if no key or no quota.
        """
        if "/" in model:
            prov, mid = model.split("/", 1)
            key = self._store.get(prov)
            if key and self._store.has_quota(prov, tokens):
                return ModelEntry(prov, mid)
            return None
        # plain — find first provider that has it cached and has quota
        for prov in self._store.providers():
            if model in self.models(prov) and self._store.has_quota(prov, tokens):
                return ModelEntry(prov, model)
        return None

    def best_available(self, tokens: int = 1000) -> ModelEntry | None:
        """Pick any model from the highest-priority provider with quota."""
        key = self._store.select(tokens)
        if key is None:
            return None
        ids = self.models(key.provider)
        if not ids:
            # provider has a key but listing not fetched/empty — return provider itself
            return ModelEntry(key.provider, "")
        return ModelEntry(key.provider, ids[0])

    # --- Introspection -----------------------------------------------------

    def cached_providers(self) -> tuple[str, ...]:
        return tuple(self._cache.keys())

    def __len__(self) -> int:
        return sum(len(v) for v in self._cache.values())

    def summary(self) -> Series[tuple[str, int]]:
        """Series of (provider, model_count) for cached providers."""
        rows = tuple((p, len(self._cache[p])) for p in self._store.providers() if p in self._cache)
        return Series.from_tuple(rows)


# ---------------------------------------------------------------------------
# Module-level singleton
# ---------------------------------------------------------------------------

_MUX: ModelMux | None = None
_MUX_LOCK = threading.Lock()


def get_mux() -> ModelMux:
    global _MUX
    if _MUX is None:
        with _MUX_LOCK:
            if _MUX is None:
                _MUX = ModelMux()
    return _MUX
