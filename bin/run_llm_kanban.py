#!/usr/bin/env python3
"""Run kanban with LLM-driven dispatch — no GEPA, direct multi-agent coordination.

Usage:
    python3.14 bin/run_llm_kanban.py
"""
from __future__ import annotations
import os
import sys
import time
import threading

# Make tspy importable whether run from repo root or bin/
_REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(_REPO, "libs", "tspy", "src", "python"))

from tspy.kanban import KanbanBoard, KeyEntry, Card, SEED_POLICY, parse_policy
from tspy.keymux import get_store
from tspy.modelmux import get_mux


# ---------------------------------------------------------------------------
# LM wrapper using keymux/modelmux
# ---------------------------------------------------------------------------

class LLM:
    """LLM wrapper using keymux for credentials and modelmux for model routing."""
    
    def __init__(self, model: str = "nvidia/nemotron-3-ultra-550b-a55b"):
        from openai import OpenAI
        
        self.model = model
        self._model = None  # Lazy load
        
        # Use keymux to get the NVIDIA API key
        store = get_store()
        nvidia_key = store.get("nvidia")
        if not nvidia_key:
            raise RuntimeError("No NVIDIA key found in keymux store")
        
        # Use modelmux to verify the model is available
        mux = get_mux()
        mux.refresh()
        if not mux.has_model(model):
            print(f"WARNING: model {model} not found, proceeding anyway")
        
        self._client = OpenAI(
            base_url=nvidia_key.base_url,
            api_key=nvidia_key.secret,
        )
    
    def _call(self, prompt: str) -> str:
        extra_body = {
            "chat_template_kwargs": {"enable_thinking": True},
            "reasoning_budget": 2048,  # Reduced for speed
        }
        completion = self._client.chat.completions.create(
            model=self.model,
            messages=[{"role": "user", "content": prompt}],
            temperature=0.7,
            max_tokens=512,  # Reduced for speed
            extra_body=extra_body,
            timeout=10.0,
        )
        return completion.choices[0].message.content or ""
    
    def dispatch_decision(self, board_state: dict) -> list[dict]:
        """Ask LLM to decide dispatch action based on board state."""
        avail = len(board_state['available_keys'])
        todo = len(board_state['todo_cards'])
        prompt = f"{avail} keys, {todo} tasks. Return [{{\"card_id\":\"task-0\",\"key_id\":\"env-kilo_code\"}}]"
        
        try:
            result = self._call(prompt)
            import json
            start = result.find('[')
            end = result.rfind(']') + 1
            if start >= 0 and end > start:
                return json.loads(result[start:end])
        except Exception as e:
            pass  # Silent fail, return empty
        return []


# ---------------------------------------------------------------------------
# Board factory
# ---------------------------------------------------------------------------

def make_board() -> KanbanBoard:
    """Build a board with keys from keymux and models from modelmux.
    
    Loads persisted state from Confix JSON if available.
    """
    store = get_store()
    mux = get_mux()
    mux.refresh()

    # Try to load persisted state
    board = KanbanBoard(store=store, mux=mux)
    if board.load():
        print(f"Loaded persisted state: {len(board.cards)} cards")
        return board
    
    # Fresh board
    keys = []
    for mk in store:
        models = mux.models(mk.provider)
        model = models[0] if models else ""
        keys.append(KeyEntry(
            key_id=mk.key_id,
            provider=mk.provider,
            label=mk.provider,
            model=model,
        ))
    
    # 20 cards for multi-agent dispatch
    cards = [
        Card(id=f"task-{i}", title=f"agent task {i}", priority=1 + (i % 3))
        for i in range(20)
    ]
    board = KanbanBoard(keys=keys, cards=cards, store=store, mux=mux)
    return board


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

def main():
    print("=" * 64)
    print("LLM-driven Kanban Dispatch — Multi-Agent Coordination")
    print("=" * 64)

    # Initialize
    print("Creating board...")
    board = make_board()
    print("Creating LLM...")
    llm = LLM()
    
    print(f"\nBoard ready: {len(board.keys)} keys, {len(board.cards)} cards")
    print(f"LLM: {llm.model}")
    print("=" * 64)

    # Run LLM-driven dispatch loop
    for tick in range(8):
        print(f"Tick {tick}: getting state...", flush=True)
        
        avail = board.available_keys()
        doing = [c for c in board.cards if c.column == "doing"]
        todo = [c for c in board.cards if c.column == "todo"]
        done = [c for c in board.cards if c.column == "done"]
        
        board_state = {
            "available_keys": [(k.key_id, k.provider) for k in avail],
            "doing_keys": [(c.id, c.assignee) for c in doing],
            "todo_cards": [(c.id, c.title, c.priority) for c in todo[:3]],
            "doing_cards": [(c.id, c.assignee) for c in doing],
            "done_cards": len(done),
            "util": board.metrics.worker_util,
        }
        
        print(f"Tick {tick}: asking LLM...", flush=True)
        decisions = llm.dispatch_decision(board_state)
        print(f"Tick {tick}: LLM returned {len(decisions)}", flush=True)
        
        # Execute dispatch decisions
        spawned = 0
        with board._lock:
            for decision in decisions:
                card_id = decision.get("card_id")
                key_id = decision.get("key_id")
                
                # Find card and key
                card = next((c for c in board.cards if c.id == card_id and c.column == "todo"), None)
                key = board.keys.get(key_id)
                
                if card and key and key.leased_to is None:
                    card.column = "doing"
                    card.assignee = key.key_id
                    key.leased_to = f"agent-{card.id}"
                    key.access_count += 1
                    key.last_used_ms = int(time.time() * 1000)
                    spawned += 1
        
        # Run tick for metrics
        policy = parse_policy(SEED_POLICY)
        result = board.tick(policy)
        
        # Simulate some completions (in real use, agents report back)
        doing = [c for c in board.cards if c.column == "doing"]
        for c in doing[:2]:
            board.complete_card(c.id)
        
        # Persist after each tick
        board.save()
        
        print(f"tick {tick + 1:2d}: spawned={spawned:2d} doing={board.doing_count():2d} "
              f"done={board.completed_count():2d} util={board.metrics.worker_util:3.0%} "
              f"llm_decisions={len(decisions)}")
        
        time.sleep(0.1)
    
    # Persist final state
    saved = board.save()
    print(f"Saved state to: {saved}")
    
    print("=" * 64)
    print("Dispatch complete")
    print(f"Total done: {board.completed_count()}/{len(board.cards)}")


if __name__ == "__main__":
    main()
