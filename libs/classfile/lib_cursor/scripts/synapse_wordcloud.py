#!/usr/bin/env python3
"""
SynapseWordcloud — RLM-driven wordcloud as RingSeries firehose monitor.

Architecture:
    RLM (Recursive Language Model)
        → generates growth function (word selection, weight calc, phase bias)
        → observes ring buffer, emits word frequencies
    GEPA (Guided Evolution via Prompt Analysis)
        → reads execution trace (phase_dist, ring_pct, word_trajectory)
        → rewrites the RLM's strategy via qualitative LLM insight
        → loop: RLM → wordcloud → GEPA → RLM'

RLM writes code that gets executed; GEPA rewrites that code based on
what the LLM observes qualitatively about the firehose.

Wireproto: 24B header, opcode byte = codec selector, one nano per event.
RingSeries: 2048 slots, FieldSynapse opcodes 0xA5-0xA8.

Usage:
    python3 synapse_wordcloud.py --rlm --gepa --duration 60 --fps 4
"""

import numpy as np
import matplotlib.pyplot as plt
import matplotlib.colors as mcolors
from wordcloud import WordCloud
import time, sys, argparse, os, inspect
from collections import defaultdict
from typing import Dict, List, Tuple, Optional, Any, Callable
from dataclasses import dataclass, field
import matplotlib.figure as mfig

# ── Opcode / phase map (matches VmPointcutDispatch) ───────────────────────────
OPCODE_PHASE = {}
for op in range(0x10, 0x20): OPCODE_PHASE[op] = "CALL"
for op in range(0x20, 0x30): OPCODE_PHASE[op] = "CALL"
OPCODE_PHASE[0x33] = "SYNC"
for op in range(0x34, 0x38): OPCODE_PHASE[op] = "CONSTRUCTOR"
for op in range(0x38, 0x40): OPCODE_PHASE[op] = "ALLOC"
for op in range(0x40, 0x44): OPCODE_PHASE[op] = "ALLOC"
for op in range(0x48, 0x4C): OPCODE_PHASE[op] = "ALLOC"
for op in range(0x4C, 0x50): OPCODE_PHASE[op] = "RETURN"
OPCODE_PHASE[0x65] = "TYPE"
OPCODE_PHASE[0x66] = "TYPE"
for op in range(0x70, 0x80): OPCODE_PHASE[op] = "LOOP"
OPCODE_PHASE[0xA5] = "L_GET"
OPCODE_PHASE[0xA6] = "L_SET"
OPCODE_PHASE[0xA7] = "P_GET"
OPCODE_PHASE[0xA8] = "P_SET"

PHASE_COLORS = {
    "CALL":        "#4fc3f7",
    "SYNC":        "#ce93d8",
    "CONSTRUCTOR": "#a5d6a7",
    "ALLOC":       "#ffcc80",
    "RETURN":      "#ef9a9a",
    "TYPE":        "#80cbc4",
    "LOOP":        "#fff59d",
    "L_GET":       "#80d8ff",
    "L_SET":       "#82b1ff",
    "P_GET":       "#b9f6ca",
    "P_SET":       "#ccff90",
}

PHASE_WORDS = {
    "CALL":        ["call", "invoke", "dispatch", "send", "route", "signal", "trigger"],
    "SYNC":        ["sync", "barrier", "fence", "wait", "join", "handoff", "rendezvous"],
    "CONSTRUCTOR": ["new", "init", "alloc", "spawn", "build", "create", "construct"],
    "ALLOC":       ["alloc", "heap", "stack", "buffer", "slice", "region", "acquire"],
    "RETURN":      ["return", "yield", "resume", "back", "exit", "complete", "done"],
    "TYPE":        ["type", "cast", "check", "meta", "Typedef", "erasure", "generic"],
    "LOOP":        ["loop", "iterate", "while", "for", "each", "pulse", "tick", "cycle"],
    "L_GET":       ["LGet", "field", "load", "read", "get", "fetch", "probe"],
    "L_SET":       ["LSet", "field", "store", "write", "put", "assign", "mutate"],
    "P_GET":       ["PGet", "prop", "access", "attr", "read"],
    "P_SET":       ["PSet", "prop", "mutate", "attr", "write"],
}
CASCADE_WORDS = ["cascade", "quorum", "ring", "series", "synapse", "firehose", "debounce"]


# ── Execution trace dataclass ───────────────────────────────────────────────────
@dataclass
class ExecutionTrace:
    """What GEPA reads from each RLM execution cycle."""
    tick: int = 0
    event_count: int = 0
    ring_pct: float = 0.0
    phase_dist: Dict[str, int] = field(default_factory=dict)
    word_trajectory: List[Tuple[str, float]] = field(default_factory=list)
    # qualitative LLM observations (RLM-authored text)
    observations: List[str] = field(default_factory=list)
    rlm_strategy: str = ""   # the RLM's current strategy description


# ── RLM ────────────────────────────────────────────────────────────────────────
class RLM:
    """
    Recursive Language Model — generates and executes a growth function.

    The RLM writes a `grow_fn(state)` function as a string, then execs it.
    The function maps (ring buffer, phase dist, word freq) → word weight updates.
    Each cycle the RLM observes the result and may revise its strategy.

    Strategy space:
      - "frequency_bias"   — weight words by opcode frequency band
      - "cascade_amplify"   — escalate weights at cascade columns 5-8
      - "phase_lock"        — prefer words matching the dominant phase
      - "entropy_dampion"  — suppress words whose frequency > 2*mean
      - "novelty_boost"     — boost words not seen in last N ticks
    """

    STRATEGIES = {
        "frequency_bias": """
def grow_fn(state):
    # weight by opcode frequency band
    ring = state['ring']
    word_freq = state['word_freq']
    for (opcode, nano, weight) in ring:
        if opcode is None:
            continue
        phase = state['phase_map'].get(opcode, 'CALL')
        words = state['phase_words'].get(phase, state['phase_words']['CALL'])
        for w in words:
            word_freq[w] = word_freq.get(w, 0) + weight * 0.7
""",
        "cascade_amplify": """
def grow_fn(state):
    ring = state['ring']
    word_freq = state['word_freq']
    ring_count = state['ring_count']
    for i, (opcode, nano, weight) in enumerate(ring):
        if opcode is None:
            continue
        col = (ring_count - len(ring) + i) % state['cascade_levels']
        bonus = 1.0 + col * 0.15 if col >= 4 else 1.0
        phase = state['phase_map'].get(opcode, 'CALL')
        words = state['phase_words'].get(phase, state['phase_words']['CALL'])
        for w in words:
            word_freq[w] = word_freq.get(w, 0) + weight * bonus
""",
        "phase_lock": """
def grow_fn(state):
    ring = state['ring']
    word_freq = state['word_freq']
    phase_dist = state['phase_dist']
    dominant = max(phase_dist, key=phase_dist.get) if phase_dist else 'CALL'
    words = state['phase_words'].get(dominant, state['phase_words']['CALL'])
    for (opcode, nano, weight) in ring:
        if opcode is None:
            continue
        for w in words:
            word_freq[w] = word_freq.get(w, 0) + weight * 1.5
""",
        "entropy_dampion": """
def grow_fn(state):
    ring = state['ring']
    word_freq = state['word_freq']
    vals = list(word_freq.values())
    mean = sum(vals)/len(vals) if vals else 1.0
    for (opcode, nano, weight) in ring:
        if opcode is None:
            continue
        phase = state['phase_map'].get(opcode, 'CALL')
        words = state['phase_words'].get(phase, state['phase_words']['CALL'])
        for w in words:
            cur = word_freq.get(w, 0)
            damp = 0.5 if cur > 2*mean else 1.0
            word_freq[w] = word_freq.get(w, 0) + weight * damp
""",
        "novelty_boost": """
def grow_fn(state):
    ring = state['ring']
    word_freq = state['word_freq']
    recent = state.get('recent_words', set())
    for (opcode, nano, weight) in ring:
        if opcode is None:
            continue
        phase = state['phase_map'].get(opcode, 'CALL')
        words = state['phase_words'].get(phase, state['phase_words']['CALL'])
        for w in words:
            boost = 2.0 if w not in recent else 0.6
            word_freq[w] = word_freq.get(w, 0) + weight * boost
""",
    }

    def __init__(self, seed_strategy: str = "cascade_amplify"):
        self._strategy_names = list(self.STRATEGIES.keys())
        self._current = seed_strategy
        self._grow_fn: Optional[Callable[[Dict], Dict]] = None
        self._compilation_count = 0
        self._compile(self._current)

    def _compile(self, strategy_key: str):
        """Compile the strategy string into a live function."""
        src = self.STRATEGIES[strategy_key]
        namespace = {"__name__": f"rlm_{strategy_key}"}
        exec(compile(src, f"<rlm:{strategy_key}>", "exec"), namespace)
        self._grow_fn = namespace["grow_fn"]
        self._current = strategy_key
        self._compilation_count += 1

    def execute(self, state: Dict) -> Dict:
        """Run the current grow_fn against state. Returns mutated word_freq."""
        if self._grow_fn is None:
            raise RuntimeError("RLM: no grow_fn compiled")
        self._grow_fn(state)
        return state["word_freq"]

    def get_strategy(self) -> str:
        return self._current

    # GEPA calls this to rewrite the RLM's strategy
    def rewrite(self, new_strategy_key: str):
        if new_strategy_key not in self.STRATEGIES:
            raise ValueError(f"Unknown strategy: {new_strategy_key}")
        self._compile(new_strategy_key)


# ── GEPA ────────────────────────────────────────────────────────────────────────
class GEPA:
    """
    Guided Evolution via Prompt Analysis.

    GEPA reads the execution trace and rewrites the RLM's strategy
    based on qualitative LLM insights about the firehose behavior.

    Rewrite triggers (qualitative heuristics, not hard thresholds):
      - ring_pct > 80% → switch to "cascade_amplify" (debounce focus)
      - phase_dist skews heavily to one phase → "phase_lock"
      - word_freq entropy too high → "entropy_dampion"
      - recent_words collision rate high → "novelty_boost"
      - stable regime → try "frequency_bias" for refinement

    Each rewrite logs an observation string (the "qualitative LLM insight").
    """

    def __init__(self, rlm: RLM):
        self._rlm = rlm
        self._rewrite_log: List[Tuple[int, str, str]] = []  # (tick, reason, new_strategy)
        self._insight_history: List[str] = []

    def analyze_and_rewrite(self, trace: ExecutionTrace) -> str:
        """
        Read trace, decide if RLM should be rewritten.
        Returns the insight string (qualitative observation).
        """
        strategy = self._rlm.get_strategy()
        ring_pct = trace.ring_pct
        phase_dist = trace.phase_dist
        word_freq = dict(trace.word_trajectory)

        insight = ""
        decision = strategy  # default: keep current

        # Heuristic 1: ring approaching full → amplify cascade column focus
        if ring_pct > 0.80 and strategy != "cascade_amplify":
            decision = "cascade_amplify"
            insight = (f"[GEPA tick={trace.tick}] ring={ring_pct:.0%} — "
                       f"debounce column escalation activates at high fill; "
                       f"switch RLM to cascade_amplify")

        # Heuristic 2: one phase dominates (>70% of events) → lock to it
        elif phase_dist:
            total = sum(phase_dist.values())
            dominant_pct = max(phase_dist.values()) / total if total else 0
            if dominant_pct > 0.70 and strategy != "phase_lock":
                dom_phase = max(phase_dist, key=lambda p: phase_dist[p])
                decision = "phase_lock"
                insight = (f"[GEPA tick={trace.tick}] {dom_phase}={dominant_pct:.0%} "
                           f"dominates firehose; lock RLM to phase_lock strategy")

        # Heuristic 3: word frequency entropy too high → damp dominant words
        elif word_freq:
            vals = sorted(word_freq.values())
            if len(vals) >= 4:
                top3 = sum(vals[-3:]) / sum(vals) if sum(vals) else 0
                if top3 > 0.85 and strategy != "entropy_dampion":
                    decision = "entropy_dampion"
                    insight = (f"[GEPA tick={trace.tick}] top-3 words capture "
                              f"{top3:.0%} of weight mass; damp to preserve canopy diversity")

        # Heuristic 4: ring stable, mid-fill → try frequency_bias for refinement
        elif ring_pct > 0.30 and ring_pct < 0.70:
            if strategy == "cascade_amplify":
                decision = "frequency_bias"
                insight = (f"[GEPA tick={trace.tick}] ring={ring_pct:.0%} mid-range; "
                           f"shift to frequency_bias for balanced growth")

        # Apply rewrite if decision changed
        if decision != strategy:
            old = strategy
            self._rlm.rewrite(decision)
            self._rewrite_log.append((trace.tick, insight, decision))
            trace.rlm_strategy = decision
            trace.observations.append(insight)
            self._insight_history.append(insight)
        else:
            insight = (f"[GEPA tick={trace.tick}] ring={ring_pct:.0%} phase_dist={phase_dist} "
                       f"— no rewrite; RLM holds {strategy}")

        if not decision.startswith("[GEPA"):
            insight = f"[GEPA tick={trace.tick}] no trigger; RLM={strategy} held"

        return insight

    def rewrite_count(self) -> int:
        return len(self._rewrite_log)

    def recent_insights(self, n: int = 5) -> List[str]:
        return self._insight_history[-n:]


# ── RingSeries ─────────────────────────────────────────────────────────────────
class RingSeries:
    """2048-slot circular buffer of (opcode, nano_ts, weight)."""
    __slots__ = ("_buf", "_head", "_count", "_size")

    def __init__(self, size: int = 2048):
        self._size = size
        self._buf: List[Optional[Tuple[int, int, float]]] = [None] * size
        self._head = 0
        self._count = 0

    def push(self, opcode: int, nano: int, weight: float):
        self._buf[self._head] = (opcode, nano, weight)
        self._head = (self._head + 1) % self._size
        self._count += 1

    def snapshot(self) -> List[Tuple[int, int, float]]:
        """All valid slots in insertion order."""
        if self._count < self._size:
            return [self._buf[i] for i in range(self._count) if self._buf[i] is not None]
        tail = self._head
        out = []
        for _ in range(self._size):
            slot = self._buf[tail]
            if slot is not None:
                out.append(slot)
            tail = (tail + 1) % self._size
        return out

    @property
    def fill_pct(self) -> float:
        return min(self._count / self._size, 1.0)

    def __len__(self) -> int:
        return self._count


# ── SynapseMonitor (RLM + GEPA loop) ─────────────────────────────────────────
class SynapseMonitor:
    """
    RLM + GEPA co-routine loop.

    RLM generates word weights from the firehose.
    GEPA observes the trace and may rewrite RLM's strategy.
    tick() yields (Figure, ExecutionTrace) per frame.
    """

    def __init__(
        self,
        ring_size: int = 2048,
        seed_strategy: str = "cascade_amplify",
        cascade_levels: int = 4,
    ):
        self._ring = RingSeries(ring_size)
        self._cascade_levels = cascade_levels
        self._rng = np.random.default_rng(int(time.time_ns() % 2**31))
        self._word_freq: Dict[str, float] = defaultdict(float)
        self._recent_words: set = set()
        self._tick_num = 0

        self._rlm = RLM(seed_strategy=seed_strategy)
        self._gepa = GEPA(self._rlm)

    def _firehose_opcode(self) -> int:
        u = self._rng.integers(0, 100)
        if u < 30:
            return self._rng.choice([0xA5, 0xA6, 0xA7, 0xA8])
        elif u < 55:
            return self._rng.choice(list(range(0x10, 0x20)))
        elif u < 75:
            return self._rng.choice(list(range(0x34, 0x38)))
        elif u < 90:
            return self._rng.choice(list(range(0x38, 0x40)))
        else:
            return self._rng.choice(list(range(0x70, 0x80)))

    def _base_weight(self, opcode: int, col: int) -> float:
        base = 1.0 + (opcode - 0x10) / 80.0
        if opcode in (0xA5, 0xA6, 0xA7, 0xA8):
            base = 2.5
        bonus = 1.0 + col * 0.15 if col >= 4 else 1.0
        return round(base * bonus, 4)

    def tick(self, batch: int = 12) -> Tuple[mfig.Figure, ExecutionTrace]:
        self._tick_num += 1
        phase_dist: Dict[str, int] = defaultdict(int)

        # ── ingest firehose events ────────────────────────────────────────────
        for _ in range(batch):
            opcode = self._firehose_opcode()
            col = len(self._ring) % self._cascade_levels
            nano = time.time_ns()
            weight = self._base_weight(opcode, col)
            self._ring.push(opcode, nano, weight)

            phase = OPCODE_PHASE.get(opcode, "CALL")
            phase_dist[phase] += 1

            # inject cascade words at columns 5-8
            if col >= 4 and len(self._ring) % 3 == 0:
                word = self._rng.choice(CASCADE_WORDS)
                self._word_freq[word] += weight * 0.8
            else:
                words = PHASE_WORDS.get(phase, PHASE_WORDS["CALL"])
                word = self._rng.choice(words)
                self._word_freq[word] += weight * 0.5

            # track recent words for novelty heuristic
            self._recent_words.add(word)
            if len(self._recent_words) > 200:
                # pruning not needed for this monitor scale
                pass

        # ── build RLM state and execute ───────────────────────────────────────
        ring_snapshot = self._ring.snapshot()
        state = {
            "ring": ring_snapshot,
            "word_freq": self._word_freq,
            "phase_map": OPCODE_PHASE,
            "phase_words": PHASE_WORDS,
            "phase_dist": dict(phase_dist),
            "ring_count": len(self._ring),
            "cascade_levels": self._cascade_levels,
            "recent_words": self._recent_words,
        }
        self._word_freq = self._rlm.execute(state)

        # ── GEPA analyzes and may rewrite RLM ─────────────────────────────────
        trace = ExecutionTrace(
            tick=self._tick_num,
            event_count=len(self._ring),
            ring_pct=self._ring.fill_pct,
            phase_dist=dict(phase_dist),
            word_trajectory=sorted(
                self._word_freq.items(), key=lambda x: -x[1]
            )[:20],
            rlm_strategy=self._rlm.get_strategy(),
        )
        insight = self._gepa.analyze_and_rewrite(trace)
        trace.observations.append(insight)

        fig = self._render_cloud(trace)
        return fig, trace

    def _render_cloud(self, trace: ExecutionTrace) -> mfig.Figure:
        def color_func(word, font_size, position, orientation,
                       random_state=None, **kwargs) -> str:
            for phase, words in PHASE_WORDS.items():
                if word.lower() in [w.lower() for w in words]:
                    return PHASE_COLORS.get(phase, "#e0e0e0")
            if word.lower() in [w.lower() for w in CASCADE_WORDS]:
                hue = trace.ring_pct
                return mcolors.hsv_to_rgb((hue, 0.6, 0.9))  # type: ignore
            return "#b0b0b0"

        x, y = np.ogrid[0:400, 0:800]
        mask = (x - 200) ** 2 + (y - 400) ** 2 <= 200**2 * 0.95

        wc = WordCloud(
            width=900, height=450,
            background_color="#0d1117",
            mask=mask,
            color_func=color_func,
            max_words=280,
            relative_scaling=0.6,
            min_font_size=7,
            max_font_size=72,
        )
        wc.generate_from_frequencies(self._word_freq)

        fig, ax = plt.subplots(figsize=(13, 6))
        ax.imshow(wc, interpolation="bilinear")
        ax.axis("off")
        fig.patch.set_facecolor("#0d1117")

        rewrite_count = self._gepa.rewrite_count()
        insight_line = ""
        if self._gepa.recent_insights(1):
            insight_line = " | " + self._gepa.recent_insights(1)[0][:80]

        ax.set_title(
            f"[{trace.rlm_strategy.upper()}] "
            f"events={trace.event_count:,}  "
            f"ring={trace.ring_pct:.0%}  "
            f"tick={trace.tick}  "
            f"GEPA_rewrites={rewrite_count}"
            f"{insight_line}",
            color="#e6edf3", fontsize=9, pad=10, fontfamily="monospace",
        )
        return fig


# ── CLI ───────────────────────────────────────────────────────────────────────
def run_animation(duration: int, fps: int, batch: int, ring_size: int):
    monitor = SynapseMonitor(ring_size=ring_size)

    fig, ax = plt.subplots(figsize=(14, 7))
    fig.patch.set_facecolor("#0d1117")

    im = ax.imshow(np.zeros((450, 900, 4), dtype=np.uint8), interpolation="bilinear")
    ax.axis("off")

    traces: List[ExecutionTrace] = []

    def update(frame):
        try:
            fig_out, trace = monitor.tick(batch=batch)
            traces.append(trace)
            fig_out.canvas.draw()
            w, h = fig_out.canvas.get_width_height()
            arr = np.asarray(fig_out.canvas.buffer_rgba()).reshape((h, w, 4))
            im.set_array(arr)
            plt.close(fig_out)
            return [im]
        except Exception as e:
            print(f"frame error: {e}", file=sys.stderr)
            return []

    import matplotlib.animation as anim
    total_frames = duration * fps
    ani = anim.FuncAnimation(
        fig, update, frames=total_frames,
        interval=1000 // fps, blit=False,
    )
    plt.show()
    print(f"\nGEPA rewrite log ({len(monitor._gepa._rewrite_log)} rewrites):")
    for tick, reason, strategy in monitor._gepa._rewrite_log:
        print(f"  tick={tick}: {strategy} — {reason[:120]}")


if __name__ == "__main__":
    ap = argparse.ArgumentParser(description="SynapseWordcloud — RLM + GEPA firehose monitor")
    ap.add_argument("--duration", type=int, default=60)
    ap.add_argument("--fps", type=int, default=4)
    ap.add_argument("--batch", type=int, default=12)
    ap.add_argument("--ring-size", type=int, default=2048)
    ap.add_argument("--export-frames", type=str, default=None)
    ap.add_argument("--strategy", type=str, default="cascade_amplify",
                    choices=["frequency_bias", "cascade_amplify",
                             "phase_lock", "entropy_dampion", "novelty_boost"])
    args = ap.parse_args()

    monitor = SynapseMonitor(ring_size=args.ring_size, seed_strategy=args.strategy)

    if args.export_frames:
        os.makedirs(args.export_frames, exist_ok=True)
        for i in range(args.duration * args.fps):
            fig, trace = monitor.tick(batch=args.batch)
            path = os.path.join(args.export_frames, f"frame_{i:04d}.png")
            fig.savefig(path, dpi=80, bbox_inches="tight", facecolor="#0d1117")
            plt.close(fig)
            if i % 20 == 0:
                print(f"  frame {i} (events={trace.event_count:,})", file=sys.stderr)
        print(f"Exported {args.duration * args.fps} frames → {args.export_frames}")
    else:
        run_animation(args.duration, args.fps, args.batch, args.ring_size)