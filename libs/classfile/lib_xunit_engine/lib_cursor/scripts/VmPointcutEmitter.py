#!/usr/bin/env python3
"""
VmPointcutEmitter.py — consumes Ghidra headless NDJSON pointcut events,
re-emits as structured NDJSON for PointcutServer.kt, and writes
the pcode_dump.json for BlackboardTimeseries.

This is the glue between:
    ghidra-xvm-scanner.py  (produces /tmp/ghidra_pointcut_out/pointcuts.ndjson)
        → this emitter (normalizes + enriches)
            → PointcutServer.kt (stdin consumer)
            → VmPointcutPublisher (RingSeries front-line)

Usage:
    python3 VmPointcutEmitter.py \
        --input /tmp/ghidra_pointcut_out/pointcuts.ndjson \
        --pcode   /tmp/ghidra_pointcut_out/pcode_dump.json \
        --output  /tmp/vm_pointcut_out.ndjson \
        --watch

Or in pipeline mode (streaming):
    tail -f /tmp/ghidra_pointcut_out/pointcuts.ndjson | python3 VmPointcutEmitter.py --stream

Wireproto: opcode byte IS the codec selector (1 byte, 0-255).
Phase mapping: CONSTRUCTOR(0x34), GETTER(0xA5/0xA7), SETTER(0xA6/0xA8), ALLOC(0x38-0x4B)
"""

import json
import sys
import os
import argparse
import threading
import time
from typing import Iterator, Optional, List, Dict, Any
from pathlib import Path


# ── Opcode → phase map (mirrors VmPointcutDispatch.java) ─────────────────

OPCODE_PHASE = {}
for op in range(0x10, 0x20):  OPCODE_PHASE[op] = "CALL"
for op in range(0x20, 0x30):  OPCODE_PHASE[op] = "CALL"
OPCODE_PHASE[0x33] = "SYNC"
for op in range(0x34, 0x38):  OPCODE_PHASE[op] = "CONSTRUCTOR"
for op in range(0x38, 0x40):  OPCODE_PHASE[op] = "ALLOC"
for op in range(0x40, 0x44):  OPCODE_PHASE[op] = "ALLOC"
for op in range(0x48, 0x4C):  OPCODE_PHASE[op] = "ALLOC"
for op in range(0x4C, 0x50):  OPCODE_PHASE[op] = "RETURN"
OPCODE_PHASE[0x65] = "TYPE"
OPCODE_PHASE[0x66] = "TYPE"
for op in range(0x70, 0x80):  OPCODE_PHASE[op] = "LOOP"
for op in range(0x90, 0x93):  OPCODE_PHASE[op] = "ASSERT"
OPCODE_PHASE.update({0xA5: "GETTER", 0xA6: "SETTER", 0xA7: "GETTER", 0xA8: "SETTER"})


def phase_of(opcode: int) -> str:
    return OPCODE_PHASE.get(opcode, "GAP")


# ── P-code index ───────────────────────────────────────────────────────────

class PcodeIndex:
    """
    Loads pcode_dump.json (from Ghidra or BlackboardTimeseries),
    builds a lookup index: method → {pcode_ops, pcode_count, calls, branches}.
    """
    def __init__(self, pcode_json_path: str):
        self.path = Path(pcode_json_path)
        self.functions: List[Dict[str, Any]] = []
        self._index: Dict[str, Dict[str, Any]] = {}
        self._load()

    def _load(self):
        if not self.path.exists():
            print(f"[PcodeIndex] WARNING: {self.path} not found — P-code enrichment disabled", file=sys.stderr)
            return
        try:
            with open(self.path) as f:
                self.functions = json.load(f)
            # Build method → fn_dict index
            for fn in self.functions:
                name = fn.get("name", "")
                self._index[name] = fn
            print(f"[PcodeIndex] Loaded {len(self.functions)} functions from {self.path}", file=sys.stderr)
        except Exception as e:
            print(f"[PcodeIndex] ERROR loading {self.path}: {e}", file=sys.stderr)

    def lookup(self, method_name: str) -> Dict[str, Any]:
        """Return P-code metadata for a method, or empty dict."""
        return self._index.get(method_name, {})

    def enrich(self, pointcut_event: dict) -> dict:
        """Enrich a pointcut event with P-code data from the index."""
        method = pointcut_event.get("method", "")
        # Strip class. prefix to match function name
        fn_name = method.split(".")[-1].split("(")[0]
        pcode = self.lookup(fn_name)
        if pcode:
            pointcut_event["pcode_count"] = pcode.get("invocations", 0)
            pointcut_event["pcode_ops"] = [op["op"] for op in pcode.get("pcode", [])[:20]]
            pointcut_event["calls"] = sum(
                1 for op in pcode.get("pcode", [])
                if op.get("op") in ("CALL", "CALLIND")
            )
            pointcut_event["branches"] = sum(
                1 for op in pcode.get("pcode", [])
                if op.get("op") in ("BRANCH", "CBRANCH", "BRANCHIND")
            )
        return pointcut_event


# ── CRUdux action builder ─────────────────────────────────────────────────

def build_crudux_action(event: dict) -> dict:
    """
    Wraps a pointcut event into a CRUdux action for PointcutServer.
    Type: CREATE (pointcut created), UPDATE (enriched), DELETE (evicted).
    """
    opcode = event.get("opcode", -1)
    method = event.get("method", "")
    phase = event.get("phase", "GAP")

    return {
        "type": "CRUDUX",
        "action": "CREATE",
        "payload": {
            "opcode": opcode,
            "phase": phase,
            "method": method,
            "addr": event.get("addr", 0),
            "pcode_count": event.get("pcode_count", 0),
            "pcode_ops": event.get("pcode_ops", []),
            "calls": event.get("calls", []),
            "branches": event.get("branches", []),
        }
    }


# ── NDJSON line parser ─────────────────────────────────────────────────────

def parse_ndjson_line(line: str) -> Optional[dict]:
    """Parse one NDJSON line, return dict or None for control messages."""
    try:
        obj = json.loads(line)
    except json.JSONDecodeError:
        return None

    # Handle DONE / ERROR control messages
    if isinstance(obj, dict):
        t = obj.get("type", "")
        if t == "DONE":
            return None
        if t == "ERROR":
            print(f"[VmPointcutEmitter] Ghidra ERROR: {obj.get('message', obj)}", file=sys.stderr)
            return None

    return obj


def iter_ndjson(path: str) -> Iterator[dict]:
    """Iterate NDJSON records from a file (or stdin)."""
    if path == "-":
        # Streaming from stdin
        for line in sys.stdin:
            line = line.strip()
            if not line:
                continue
            evt = parse_ndjson_line(line)
            if evt is not None:
                yield evt
    else:
        with open(path) as f:
            for line in f:
                line = line.strip()
                if not line:
                    continue
                evt = parse_ndjson_line(line)
                if evt is not None:
                    yield evt


# ── Re-emit enriched NDJSON ────────────────────────────────────────────────

class VmPointcutEmitter:
    """
    Reads Ghidra NDJSON pointcut events, enriches from P-code index,
    re-emits as CRUdux-formatted NDJSON for PointcutServer.kt.

    Output format:
        {"type":"CRUDUX","action":"CREATE","payload":{pointcut_event}}
        {"type":"POINTcut",...}   (passthrough original)

    The NDJSON stream is the wireproto for VmPointcutPublisher.publish().
    """

    def __init__(self, pcode_index: Optional[PcodeIndex] = None):
        self.pcode_index = pcode_index or PcodeIndex("")
        self.seq = 0
        self.emitted = 0

    def emit(self, event: dict, out=sys.stdout) -> dict:
        """Process and emit one event, return the emitted dict."""
        opcode = event.get("opcode", -1)
        phase = event.get("phase", phase_of(opcode))

        # Ensure phase is set
        event["phase"] = phase

        # Enrich from P-code index
        if self.pcode_index:
            event = self.pcode_index.enrich(event)

        # Tag with sequence number
        event["seq"] = self.seq
        self.seq += 1

        # Emit CRUdux action
        crudux = build_crudux_action(event)

        out.write(json.dumps(crudux, separators=(",", ":")) + "\n")
        self.emitted += 1

        # Also emit the raw pointcut (for backward compat)
        out.write(json.dumps(event, separators=(",", ":")) + "\n")

        return event

    def run(self, input_path: str, output_path: Optional[str] = None, stream: bool = False):
        """
        Full run: read input NDJSON, emit enriched events.
        If stream=True, tail the input file for new records.
        """
        out_f = None
        try:
            if output_path and output_path != "-":
                out_f = open(output_path, "w")
                out = out_f
            else:
                out = sys.stdout

            if stream:
                print(f"[VmPointcutEmitter] Streaming mode — watching {input_path}", file=sys.stderr)
                self._run_stream(input_path, out)
            else:
                self._run_batch(input_path, out)
        finally:
            if out_f:
                out_f.close()

    def _run_batch(self, input_path: str, out):
        for event in iter_ndjson(input_path):
            self.emit(event, out)
        print(f"[VmPointcutEmitter] Batch done — {self.emitted} events emitted", file=sys.stderr)

    def _run_stream(self, input_path: str, out):
        """Tail-read input file, emit new records as they appear."""
        path = Path(input_path)
        if not path.exists():
            print(f"[VmPointcutEmitter] ERROR: {input_path} does not exist", file=sys.stderr)
            return

        # Track position
        pos = 0
        while True:
            try:
                with open(path) as f:
                    f.seek(pos)
                    for line in f:
                        line = line.strip()
                        if not line:
                            continue
                        evt = parse_ndjson_line(line)
                        if evt is not None:
                            self.emit(evt, out)
                    pos = f.tell()
            except Exception as e:
                print(f"[VmPointcutEmitter] Stream error: {e}", file=sys.stderr)
            time.sleep(0.5)


# ── Watchdog: emit DONE marker periodically ──────────────────────────────────

def emit_done(out=sys.stdout):
    out.write(json.dumps({"type": "DONE"}, separators=(",", ":")) + "\n")


# ── CLI entry point ────────────────────────────────────────────────────────

def main():
    parser = argparse.ArgumentParser(
        description="VmPointcutEmitter — Ghidra NDJSON → PointcutServer NDJSON",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
Examples:
  # Batch: enrich pointcuts.ndjson and write to stdout
  python3 VmPointcutEmitter.py --input /tmp/ghidra_pointcut_out/pointcuts.ndjson \\
       --pcode /tmp/ghidra_pointcut_out/pcode_dump.json

  # Streaming: tail Ghidra output, pipe to PointcutServer
  tail -f /tmp/ghidra_pointcut_out/pointcuts.ndjson | \\
  python3 VmPointcutEmitter.py --stream --pcode /tmp/ghidra_pointcut_out/pcode_dump.json

  # Full pipeline with PointcutServer
  tail -f /tmp/ghidra_pointcut_out/pointcuts.ndjson | \\
  python3 VmPointcutEmitter.py --stream | \\
  java -cp lib_cursor.jar org.xvm.cursor.PointcutServerKt
        """
    )
    parser.add_argument("--input", "-i", default="/tmp/ghidra_pointcut_out/pointcuts.ndjson",
                        help="Input NDJSON from ghidra-xvm-scanner.py (default: /tmp/ghidra_pointcut_out/pointcuts.ndjson)")
    parser.add_argument("--pcode", "-p", default="/tmp/ghidra_pointcut_out/pcode_dump.json",
                        help="P-code JSON from ghidra-xvm-scanner.py (default: /tmp/ghidra_pointcut_out/pcode_dump.json)")
    parser.add_argument("--output", "-o", default="-",
                        help="Output NDJSON path (default: - = stdout)")
    parser.add_argument("--stream", "-s", action="store_true",
                        help="Stream/tail mode — watch input file for new records")
    args = parser.parse_args()

    # Build P-code index
    pcode_index = PcodeIndex(args.pcode) if args.pcode else None

    # Run emitter
    emitter = VmPointcutEmitter(pcode_index=pcode_index)
    emitter.run(args.input, args.output if args.output != "-" else None, stream=args.stream)

    if not args.stream:
        emit_done()


if __name__ == "__main__":
    main()