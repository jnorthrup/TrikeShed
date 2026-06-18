#!/usr/bin/env python3
"""Run GEPA to evolve tspy kanban dispatch policy — production mode.

Uses GEPA with NVIDIA Nemotron-3-Ultra via OpenAI client (bypasses litellm).

Usage:
    python3.14 bin/run_gepa_kanban.py
"""
from __future__ import annotations
import os
import random
import sys
import threading
from typing import Any

# Make tspy importable whether run from repo root or bin/
_REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(_REPO, "libs", "tspy", "src", "python"))

from tspy.kanban import KanbanBoard, KeyEntry, Card, SEED_POLICY, parse_policy
from tspy.keymux import get_store
from tspy.modelmux import get_mux


# ---------------------------------------------------------------------------
# Nemotron LM wrapper — uses keymux/modelmux for credentials and model listings
# ---------------------------------------------------------------------------

class NemotronLM:
    """OpenAI-compatible LM wrapper for NVIDIA Nemotron via integrate.api.nvidia.com.
    
    Uses tspy.keymux for credential lookup and tspy.modelmux for model routing.
    """

    def __init__(
        self,
        model: str = "nvidia/nemotron-3-ultra-550b-a55b",
        temperature: float | None = 1.0,
        max_tokens: int | None = 16384,
        num_retries: int = 3,
        **kwargs: Any,
    ):
        from openai import OpenAI
        self.model = model
        self.num_retries = num_retries
        self._total_cost = 0.0
        self._total_tokens_in = 0
        self._total_tokens_out = 0
        self._cost_lock = threading.Lock()

        # Use keymux to get the NVIDIA API key
        from tspy.keymux import get_store
        store = get_store()
        nvidia_key = store.get("nvidia")
        if not nvidia_key:
            raise RuntimeError("No NVIDIA key found in keymux store")
        self._api_key = nvidia_key.secret
        self._base_url = nvidia_key.base_url

        # Use modelmux to verify the model is available
        from tspy.modelmux import get_mux
        self._mux = get_mux()
        # Refresh model listings first (lazy fetch from NVIDIA)
        self._mux.refresh()
        available = self._mux.has_model(model)
        if not available:
            print(f"WARNING: model {model} not found in NVIDIA listings, proceeding anyway")

        self._client = OpenAI(
            base_url=self._base_url,
            api_key=self._api_key,
        )
        self.completion_kwargs: dict[str, Any] = {
            **({"temperature": temperature} if temperature is not None else {}),
            **({"max_tokens": max_tokens} if max_tokens is not None else {}),
            **kwargs,
        }

    def __call__(self, prompt: str | list[dict[str, Any]]) -> str:
        if isinstance(prompt, str):
            messages: list[dict[str, Any]] = [{"role": "user", "content": prompt}]
        else:
            messages = prompt

        # Add thinking/reasoning params for Nemotron
        extra_body = {
            "chat_template_kwargs": {"enable_thinking": True},
            "reasoning_budget": 16384,
        }

        last_exc = None
        for attempt in range(self.num_retries):
            try:
                completion = self._client.chat.completions.create(
                    model=self.model,
                    messages=messages,
                    extra_body=extra_body,
                    **self.completion_kwargs,
                )
                content = completion.choices[0].message.content or ""
                # Track usage
                usage = getattr(completion, "usage", None)
                if usage:
                    with self._cost_lock:
                        self._total_tokens_in += getattr(usage, "prompt_tokens", 0) or 0
                        self._total_tokens_out += getattr(usage, "completion_tokens", 0) or 0
                return content
            except Exception as e:
                last_exc = e
                if attempt == self.num_retries - 1:
                    raise
        raise last_exc


# ---------------------------------------------------------------------------
# Board factory — what GEPA dispatches against each evaluation
# ---------------------------------------------------------------------------

def make_board() -> KanbanBoard:
    """Build a board seeded from the real keymux store.

    Each key from keymux represents one quota/instance.
    Uses modelmux to cache /models listings per provider, assigns most recent model.
    """
    store = get_store()
    mux = get_mux()
    
    # Ensure model listings are cached (lazy fetch via /models)
    mux.refresh()

    real_keys = list(store)
    if real_keys:
        keys = []
        for mk in real_keys:
            # Get cached models for this provider (modelmux lazy-fetches via /models)
            models = mux.models(mk.provider)
            # Use the most recent model (first in list) from cached /models response
            model = models[0] if models else ""
            keys.append(KeyEntry(
                key_id=mk.key_id,
                provider=mk.provider,
                label=mk.provider,
                model=model,
            ))
    else:
        # synthetic fallback when no env keys present
        keys = [
            KeyEntry(key_id=f"key-{i}", provider="modelmux",
                     label=f"solver-{i}", model="")
            for i in range(8)
        ]
    cards = [
        Card(id=f"task-{i}", title=f"solver task {i}", priority=1 + (i % 3))
        for i in range(20)
    ]
    return KanbanBoard(keys=keys, cards=cards, store=store, mux=mux)


def evaluate(candidate: str, *args, **kwargs) -> float:
    """Run the candidate policy through several ticks; return aggregate score.

    Simulates real work: cards complete after 1-2 ticks, freeing keys for new work.
    """
    policy = parse_policy(candidate)
    board = make_board()
    total = 0.0
    for tick in range(6):
        r = board.tick(policy)
        total += r.score
        # Simulate real completions: complete up to 2 doing cards per tick
        doing = [c for c in board.cards if c.column == "doing"]
        for c in doing[:2]:
            board.complete_card(c.id)
    return total


# ---------------------------------------------------------------------------
# Custom candidate proposer — no LLM, pure mutation (fallback)
# ---------------------------------------------------------------------------

_NUMERIC_KEYS = {
    "max_in_progress": (1, 16),
    "max_spawn": (1, 16),
    "lease_ttl_ms": (10000, 600000),
    "tick_interval_ms": (1000, 30000),
    "priority_weight": (0.5, 3.0),
    "util_target": (0.3, 0.95),
}


def custom_proposer(candidate, reflective_dataset, components_to_update):
    """Mutate the current policy text. GEPA passes a dict; str-mode uses
    the 'current_candidate' key."""
    from gepa.optimize_anything import _STR_CANDIDATE_KEY
    text = candidate[_STR_CANDIDATE_KEY] if _STR_CANDIDATE_KEY in candidate else candidate.get("policy", "")
    policy = parse_policy(text)
    # pick a random numeric key to mutate
    key = random.choice(list(_NUMERIC_KEYS.keys()))
    lo, hi = _NUMERIC_KEYS[key]
    cur = policy.get(key, (lo + hi) / 2)
    if isinstance(cur, bool):
        cur = float(lo)
    span = hi - lo
    new_val = max(lo, min(hi, float(cur) + random.uniform(-span * 0.2, span * 0.2)))
    if key in ("max_in_progress", "max_spawn", "lease_ttl_ms", "tick_interval_ms"):
        new_val = int(round(new_val))
    policy[key] = new_val
    new_text = "".join(f"{k}={v}\n" for k, v in policy.items())
    return {_STR_CANDIDATE_KEY: new_text}


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

def main():
    from gepa.optimize_anything import (
        optimize_anything, GEPAConfig, EngineConfig, ReflectionConfig,
    )

    config = GEPAConfig(
        engine=EngineConfig(
            max_metric_calls=20,
            raise_on_exception=False,
            use_cloudpickle=False,
            cache_evaluation=False,
        ),
        reflection=ReflectionConfig(
            reflection_lm=NemotronLM(),
            custom_candidate_proposer=custom_proposer,
        ),
    )

    print("=" * 64)
    print("GEPA tspy kanban optimizer — NVIDIA Nemotron-3-Ultra")
    print("Seed policy:")
    print(SEED_POLICY)

    # Show the keys and models the board will dispatch with
    store = get_store()
    mux = get_mux()
    mux.refresh()
    real_keys = list(store)
    print(f"\nKeys: {len(real_keys)} providers loaded, each key = 1 quota/instance")
    for mk in real_keys:
        models = mux.models(mk.provider)
        model = models[0] if models else "(none)"
        print(f"  {mk.key_id:20s} | {mk.provider:12s} | model: {model}")
    print("=" * 64)

    result = optimize_anything(
        seed_candidate=SEED_POLICY,
        evaluator=evaluate,
        objective=(
            "Maximize kanban dispatch throughput while minimizing latency, "
            "errors, and worker imbalance. Evolve the numeric policy "
            "parameters for best forge-solver coordination."
        ),
        config=config,
    )

    best = result.best_candidate
    best_score = result.val_aggregate_scores[result.best_idx]

    print()
    print("=" * 64)
    print("GEPA COMPLETE")
    print(f"  best score:     {best_score:.3f}")
    print(f"  metric calls:   {result.total_metric_calls}")
    print(f"  candidates:     {result.num_candidates}")
    print("  best candidate:")
    print(best if isinstance(best, str) else best.get("current_candidate", str(best)))
    print("=" * 64)

    # Run the evolved policy against a fresh board to show coordination
    print("\nRunning evolved policy against fresh board:")
    policy = parse_policy(best if isinstance(best, str) else SEED_POLICY)
    board = make_board()
    for t in range(8):
        r = board.tick(policy)
        print(f"  tick {t + 1}: spawned={r.spawned:2d} doing={board.doing_count():2d} "
              f"done={board.completed_count():2d} "
              f"score={r.score:7.2f} util={board.metrics.worker_util:3.0%}")


if __name__ == "__main__":
    main()