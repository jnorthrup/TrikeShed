"""tspy.keymux — credential key store and quota tracking.

Kernel-algebra port of libs/keymux + libs/modelmux KeyMux.kt.

Shapes follow the TrikeShed kernel contract:
  - Join<A,B>  = (a, b)
  - Twin<T>    = (t, t)
  - Series<T>  = size paired with index oracle
  - projection via α (lazy map), materialization last

KeyEntry is a Join<provider, key> carrying quota Twin<used, limit>.
KeyStore is a Series<KeyEntry> indexed by priority, lazily materialized.
"""

from __future__ import annotations
from typing import Any, Callable, Iterator, Tuple
from dataclasses import dataclass, field
import os
import threading
import time

from tspy.algebra import Join, j, Series, s_


# ---------------------------------------------------------------------------
# Provider specifications — mirrors Kotlin ProviderSpec table
# ---------------------------------------------------------------------------

@dataclass(frozen=True, slots=True)
class ProviderSpec:
    """Provider descriptor: name j priority j key_envs j base_url j is_free j limits."""
    name: str
    priority: int
    key_envs: tuple[str, ...]
    base_url: str
    is_free: bool
    daily_limit: int | None = None
    hourly_limit: int | None = None
    monthly_limit: int | None = None


def _load_env_file(path: str) -> dict[str, str]:
    """Parse a .env file into a dict. Stdlib only — no hermes dependency."""
    import os
    out: dict[str, str] = {}
    if not os.path.isfile(path):
        return out
    try:
        with open(path) as f:
            for line in f:
                line = line.strip()
                if not line or line.startswith("#"):
                    continue
                if "=" not in line:
                    continue
                k, v = line.split("=", 1)
                k = k.strip()
                v = v.strip().strip("'\"")
                if k and v:
                    out[k] = v
    except OSError:
        pass
    return out


def _merged_env() -> dict[str, str]:
    """System env merged with ~/.hermes/.env (hermes file is read as config data only)."""
    env: dict[str, str] = {}
    # ~/.hermes/.env first (lower precedence)
    env.update(_load_env_file(os.path.expanduser("~/.hermes/.env")))
    # system env overrides
    env.update(dict(os.environ))
    return env


# The canonical provider table (priority ascending = preferred first).
PROVIDERS: tuple[ProviderSpec, ...] = (
    ProviderSpec("kilo_code",  1, ("KILOCODE_API_KEY", "KILOAI_API_KEY", "KILO_CODE_API_KEY", "KILO_API_KEY"), "https://api.kilo.ai/api/gateway", True, 1_000_000, 100_000),
    ProviderSpec("moonshot",   2, ("MOONSHOTAI_API_KEY", "KIMI_API_KEY", "MOONSHOT_API_KEY"), "https://api.moonshot.cn/v1", True, 500_000, 50_000),
    ProviderSpec("deepseek",   3, ("DEEPSEEK_API_KEY",), "https://api.deepseek.com/v1", True, 500_000, 50_000),
    ProviderSpec("nvidia",     4, ("NVIDIA_API_KEY",), "https://integrate.api.nvidia.com/v1", True, 500_000, 50_000),
    ProviderSpec("zenmux",     5, ("ZENMUX_API_KEY",), "https://zenmux.ai/api/v1", True, 500_000, 50_000),
    ProviderSpec("opencode",   6, ("OPENCODE_API_KEY",), "https://api.opencode.ai", True, 250_000, 25_000),
    ProviderSpec("groq",       7, ("GROQ_API_KEY",), "https://api.groq.com/openai/v1", False),
    ProviderSpec("openai",     8, ("OPENAI_API_KEY",), "https://api.openai.com/v1", False),
    ProviderSpec("anthropic",  9, ("ANTHROPIC_API_KEY",), "https://api.anthropic.com/v1", False),
    ProviderSpec("openrouter", 10, ("OPENROUTER_API_KEY",), "https://openrouter.ai/api/v1", False),
    ProviderSpec("cerebras",   11, ("CEREBRAS_API_KEY",), "https://api.cerebras.ai/v1", False),
    ProviderSpec("xai",        12, ("XAI_API_KEY", "GROK_API_KEY"), "https://api.x.ai/v1", False),
    ProviderSpec("gemini",     13, ("GEMINI_API_KEY",), "https://generativelanguage.googleapis.com/v1beta", False),
    ProviderSpec("perplexity", 14, ("PERPLEXITY_API_KEY",), "https://www.perplexity.ai", False),
    # Providers found in ~/.hermes/.env config (read as data, not via hermes imports)
    ProviderSpec("zai",        15, ("ZAI_API_KEY", "GLM_API_KEY"), "https://api.z.ai/api/coding/paas/v4", False),
    ProviderSpec("minimax",    16, ("MINIMAX_API_KEY",), "https://api.minimax.io/anthropic", False),
)

_PROVIDER_INDEX: dict[str, ProviderSpec] = {p.name: p for p in PROVIDERS}


# ---------------------------------------------------------------------------
# Quota — Twin<used, limit> with timeframe reset
# ---------------------------------------------------------------------------

@dataclass
class ProviderQuota:
    """Quota tracking per provider. used j limit as a Twin per timeframe."""
    provider: str
    tokens_today: int = 0
    tokens_hour: int = 0
    tokens_month: int = 0
    daily_limit: int | None = None
    hourly_limit: int | None = None
    monthly_limit: int | None = None
    _last_day: int = field(default_factory=lambda: time.localtime().tm_mday)
    _last_hour: int = field(default_factory=lambda: time.localtime().tm_hour)
    _last_month: int = field(default_factory=lambda: time.localtime().tm_mon)

    def _reset_if_needed(self) -> None:
        t = time.localtime()
        if t.tm_mday != self._last_day:
            self.tokens_today = 0
            self._last_day = t.tm_mday
        if t.tm_hour != self._last_hour:
            self.tokens_hour = 0
            self._last_hour = t.tm_hour
        if t.tm_mon != self._last_month:
            self.tokens_month = 0
            self._last_month = t.tm_mon

    def has_quota(self, tokens: int) -> bool:
        self._reset_if_needed()
        return (self.daily_limit is None or self.tokens_today + tokens <= self.daily_limit) and \
               (self.hourly_limit is None or self.tokens_hour + tokens <= self.hourly_limit) and \
               (self.monthly_limit is None or self.tokens_month + tokens <= self.monthly_limit)

    def record_usage(self, tokens: int) -> None:
        self._reset_if_needed()
        self.tokens_today += tokens
        self.tokens_hour += tokens
        self.tokens_month += tokens

    @property
    def remaining_today(self) -> int:
        self._reset_if_needed()
        if self.daily_limit is None:
            return 2**63 - 1
        return max(0, self.daily_limit - self.tokens_today)

    def as_twin(self) -> Tuple[int, int | None]:
        """used j limit for today (Twin<int, int|None>)."""
        self._reset_if_needed()
        return (self.tokens_today, self.daily_limit)


# ---------------------------------------------------------------------------
# KeyEntry — Join<ProviderSpec, secret> carrying quota
# ---------------------------------------------------------------------------

@dataclass(frozen=True, slots=True)
class KeyEntry:
    """
    A loaded credential key.

    Read as: provider j secret, with quota and lease tracking attached.
    Immutable; quota mutated through KeyStore which owns ProviderQuota instances.
    """
    key_id: str
    provider: str
    secret: str
    base_url: str
    is_free: bool = False
    priority: int = 99

    @property
    def spec(self) -> ProviderSpec | None:
        return _PROVIDER_INDEX.get(self.provider)

    def pair(self) -> Join[str, str]:
        """(provider, secret) — the Join form."""
        return j(self.provider, self.secret)


# ---------------------------------------------------------------------------
# KeyStore — Series<KeyEntry> indexed by priority, lazy env scan
# ---------------------------------------------------------------------------

class KeyStore:
    """
    Lazy credential store. Scans environment on first access, caches loaded
    keys as a Series<KeyEntry> ordered by provider priority.

    Provides quota-aware provider selection: select(tokens) -> KeyEntry | None.
    """

    def __init__(self, env: dict[str, str] | None = None, providers: tuple[ProviderSpec, ...] = PROVIDERS):
        # Default: system env + ~/.hermes/.env merged (hermes read as config data only)
        self._env = env if env is not None else _merged_env()
        self._providers = providers
        self._keys: dict[str, KeyEntry] = {}
        self._quotas: dict[str, ProviderQuota] = {}
        self._loaded = False
        self._lock = threading.Lock()

    def _load(self) -> None:
        if self._loaded:
            return
        with self._lock:
            if self._loaded:
                return
            for spec in self._providers:
                key_env = next((e for e in spec.key_envs if self._env.get(e)), None)
                if key_env is None:
                    continue
                secret = self._env[key_env]
                base = self._env.get(f"{spec.name.upper()}_BASE_URL") or spec.base_url
                entry = KeyEntry(
                    key_id=f"env-{spec.name}",
                    provider=spec.name,
                    secret=secret,
                    base_url=base,
                    is_free=spec.is_free,
                    priority=spec.priority,
                )
                self._keys[spec.name] = entry
                self._quotas[spec.name] = ProviderQuota(
                    provider=spec.name,
                    daily_limit=spec.daily_limit,
                    hourly_limit=spec.hourly_limit,
                    monthly_limit=spec.monthly_limit,
                )
            self._loaded = True

    # --- Series view -------------------------------------------------------

    def keys_series(self) -> Series[KeyEntry]:
        """Return loaded keys as a Series ordered by priority (lazy projection)."""
        self._load()
        ordered = sorted(self._keys.values(), key=lambda k: k.priority)
        return Series.from_tuple(tuple(ordered))

    def __iter__(self) -> Iterator[KeyEntry]:
        return iter(self.keys_series())

    def __len__(self) -> int:
        self._load()
        return len(self._keys)

    # --- Lookup ------------------------------------------------------------

    def get(self, provider: str) -> KeyEntry | None:
        self._load()
        return self._keys.get(provider)

    def providers(self) -> tuple[str, ...]:
        self._load()
        return tuple(sorted(self._keys.keys(), key=lambda p: self._keys[p].priority))

    def quota(self, provider: str) -> ProviderQuota | None:
        self._load()
        return self._quotas.get(provider)

    def has_quota(self, provider: str, tokens: int = 1000) -> bool:
        q = self.quota(provider)
        return q.has_quota(tokens) if q else False

    def record_usage(self, provider: str, tokens: int) -> None:
        q = self.quota(provider)
        if q:
            q.record_usage(tokens)

    # --- Selection ---------------------------------------------------------

    def select(self, tokens: int = 1000) -> KeyEntry | None:
        """Best available provider with quota (lowest priority number wins)."""
        self._load()
        for entry in self.keys_series():
            if self.has_quota(entry.provider, tokens):
                return entry
        return None

    def select_for_model(self, model: str, tokens: int = 1000) -> KeyEntry | None:
        """Route a model id (provider/model or plain) to a KeyEntry."""
        self._load()
        prefix = model.split("/", 1)[0]
        if prefix in self._keys:
            entry = self._keys[prefix]
            if self.has_quota(entry.provider, tokens):
                return entry
            return None
        # plain model name — best available provider
        return self.select(tokens)

    def status(self) -> Series[tuple[str, int, int, bool]]:
        """Series of (provider, used_today, remaining_today, has_quota)."""
        self._load()
        rows = []
        for entry in self.keys_series():
            q = self._quotas[entry.provider]
            rows.append((entry.provider, q.tokens_today, q.remaining_today, q.has_quota(1)))
        return Series.from_tuple(tuple(rows))


# ---------------------------------------------------------------------------
# Module-level singleton (lazy)
# ---------------------------------------------------------------------------

_STORE: KeyStore | None = None
_STORE_LOCK = threading.Lock()


def get_store() -> KeyStore:
    global _STORE
    if _STORE is None:
        with _STORE_LOCK:
            if _STORE is None:
                _STORE = KeyStore()
    return _STORE
