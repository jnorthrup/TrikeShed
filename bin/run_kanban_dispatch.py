#!/usr/bin/env python3
"""Run kanban dispatch with a fixed policy — no GEPA, just observe behavior."""

import os
import sys
import time

_REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(_REPO, "libs", "tspy", "src", "python"))

from tspy.kanban import KanbanBoard, KeyEntry, Card, SEED_POLICY, parse_policy
from tspy.keymux import get_store
from tspy.modelmux import get_mux


def make_board() -> KanbanBoard:
    store = get_store()
    mux = get_mux()
    mux.refresh()

    real_keys = list(store)
    if real_keys:
        keys = []
        for mk in real_keys:
            models = mux.models(mk.provider)
            model = models[0] if models else ""
            keys.append(KeyEntry(
                key_id=mk.key_id,
                provider=mk.provider,
                label=mk.provider,
                model=model,
            ))
    else:
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


def main():
    policy = parse_policy(SEED_POLICY)
    board = make_board()

    print("=" * 64)
    print("Kanban dispatch — fixed seed policy")
    print(f"Policy: {policy}")
    print(f"Keys: {len(board.keys)}, Cards: {len(board.cards)}")
    print("=" * 64)

    for tick in range(10):
        result = board.tick(policy)
        metrics = board.get_metrics_summary()
        print(f"tick {tick + 1:2d}: "
              f"spawned={result.spawned:2d} "
              f"doing={metrics['doing']:2d} "
              f"done={metrics['done']:2d} "
              f"todo={metrics['todo']:2d} "
              f"score={result.score:7.2f} "
              f"util={metrics['worker_util']:.1%}")

        # Manual completion to see effect — uncomment to simulate workers finishing
        if tick >= 2:
            doing = [c for c in board.cards if c.column == "doing"]
            for c in doing[:2]:
                board.complete_card(c.id)

        time.sleep(0.1)

    print("=" * 64)
    print("Final metrics:", board.get_metrics_summary())


if __name__ == "__main__":
    main()