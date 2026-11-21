#!/usr/bin/env python3
"""LCNC airgap demonstrator: baseline curation use cases (D0/D1 vertical slice).

  python3 demo.py run [--scenario all|uc1..uc6] [--model q4bert|none|jeva] [--model-bundle DIR]
                      [--airgap egress-blocked|application-local] [--run-root DIR]
  python3 demo.py replay RUN_DIR

run     Builds a versioned LCNC graph and executes the use cases against the real engines: Stanza frames,
        operator logic with its deterministic and ontological weights, and one model lane, a 4-bit BERT
        encoder that scores operator cues and writes no text. No generative language model runs unless
        --model jeva asks for the earlier Jeva decision lane. Every artifact is retained in a run CAS outside the repo,
        with a durable journal, a check report, the articulated operator logic and an inventory.
        Exit 1 on any failed check; exit 2 when the requested egress control cannot be established.
replay  Reads the journal and prints the trace. No engine or effect runs.

--airgap egress-blocked (default) re-executes the run under a kernel sandbox that denies every network
operation to it and its descendants, probes that the denial holds, and starts no engine otherwise.
--airgap application-local runs engines behind the in-process ledger only and makes no airgap claim.
Either way this is not the physically disconnected profile (I14): the host keeps its interfaces.
"""
from __future__ import annotations

import argparse
import json
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from lcnc import airgap, inventory
from lcnc.cas import Cas
from lcnc.graph import Graph
from lcnc.journal import Journal
from lcnc.review import ReviewLedger
from lcnc.scenarios import REQUIRES, USE_CASES, Ctx, write_articulation

DEFAULT_RUN_ROOT = Path.home() / ".local/share/TrikeShed/lcnc-demo/runs"
MODEL_NODES = {
    "q4bert": {"id": "eng-q4bert", "type": "engine", "context": "derived",
               "config": {"lane": "optional-model", "kind": "encoder", "quantization": "q4g32 linear, q8g64 embeddings",
                          "maxTokens": 512, "optional": True, "generative": False, "causalLanguageModel": False}},
    "jeva": {"id": "eng-jeva", "type": "engine", "context": "derived",
             "config": {"lane": "optional-model", "backend": "mlx-q4g32-dist", "tokenmax": 2048, "optional": True,
                        "generative": False, "causalLanguageModel": True}},
}


def baseline_graph(model: str) -> Graph:
    nodes = [
        {"id": "src-coinbase", "type": "source", "context": "asserted",
         "config": {"fixture": "F01-lite", "retention": "immutable-cas"}},
        {"id": "tf-extract", "type": "transform", "context": "derived",
         "config": {"slice": "preamble-through-account-terms", "anchors": True}},
        {"id": "eng-stanza", "type": "engine", "context": "derived",
         "config": {"lane": "deterministic", "cpu": True, "optional": False, "generative": False}},
        {"id": "eng-operators", "type": "engine", "context": "derived",
         "config": {"lane": "deterministic", "cpu": True, "optional": False, "generative": False, "models": 0,
                    "weights": ["deterministic", "ontological"]}},
        {"id": "eng-conditional", "type": "engine", "context": "hypothetical",
         "config": {"lane": "deterministic", "logic": "propositional-conditional", "generative": False}},
        {"id": "panel-curation", "type": "panel", "context": "asserted",
         "config": {"seats": 5, "depth": 4, "workset": 64, "review": "explicit"}},
        {"id": "sink-records", "type": "sink", "context": "derived",
         "config": {"kind": "curation-records"}},
        {"id": "sink-review", "type": "sink", "context": "asserted",
         "config": {"kind": "review-and-productions"}},
    ]
    edges = [("src-coinbase", "tf-extract"), ("tf-extract", "eng-stanza"), ("eng-stanza", "eng-operators"),
             ("eng-stanza", "panel-curation"), ("eng-operators", "panel-curation"),
             ("eng-operators", "eng-conditional"), ("eng-conditional", "panel-curation"),
             ("panel-curation", "sink-records"), ("panel-curation", "sink-review")]
    if model in MODEL_NODES:
        node = MODEL_NODES[model]
        nodes.append(node)
        edges += [("eng-operators" if model == "q4bert" else "tf-extract", node["id"]), (node["id"], "panel-curation")]
    return Graph(nodes, edges, label="baseline-curation-v2")


def new_run_dir(root: Path):
    stamp = time.strftime("%Y%m%d-%H%M%S")
    epoch, n = stamp, 1
    while (root / epoch).exists():
        n += 1
        epoch = "%s-%d" % (stamp, n)
    (root / epoch).mkdir(parents=True)
    return epoch, root / epoch


def close_airgap(ctx: Ctx, launcher, guard, profile: str, model: str, graph: Graph) -> dict:
    """The run's own account of its egress and of which lanes ran, as checks and as report data."""
    ledger = launcher.ledger()
    summary = airgap.summarize(ledger, profile)
    harness = guard.snapshot()
    ctx.check("AIRGAP", "every Python engine process reported its own egress ledger",
              summary["ledgersReported"] == summary["pythonProcesses"],
              {"processes": summary["pythonProcesses"], "ledgers": summary["ledgersReported"]}, "one ledger per process")
    ctx.check("AIRGAP", "no process attempted a connection, send or name lookup beyond loopback",
              summary["externalAttempts"] == 0 and harness["external"] == 0,
              {"external": summary["externalAttempts"] + harness["external"],
               "loopbackRefused": summary["loopbackAttempts"] + harness["loopback"]}, {"external": 0})
    if profile == "egress-blocked":
        ctx.check("AIRGAP", "the kernel denial was in force inside the harness and every Python engine process",
                  summary["kernelDeniedEverywhere"] and harness["kernelDenied"] is True,
                  {"engines": summary["kernelDeniedEverywhere"], "harness": harness["kernelDenied"]}, True)
    engines = {n["id"]: n["config"] for n in graph.nodes.values() if n["type"] == "engine"}
    names = [p["name"] for p in launcher.inventory]
    if model != "jeva":
        ctx.check("LANES", "no causal or generative language model ran: the added model is the 4-bit BERT encoder, or none",
                  not any(c.get("causalLanguageModel") or c.get("generative") for c in engines.values())
                  and not any("jeva" in n for n in names),
                  {"engines": sorted(engines), "processes": sorted(set(names))}, "no generative or causal-language-model engine")
    ctx.journal.emit("egress-ledger", refs=[], summary={k: v for k, v in summary.items() if k != "refused"},
                     refused=summary["refused"], harness=harness)
    return {"summary": summary, "harness": harness, "processes": ledger["processes"],
            "descendants": ledger["descendants"]}


def run(args) -> int:
    profile = args.airgap
    if profile == "egress-blocked":
        try:
            airgap.enter([str(Path(__file__).resolve())] + sys.argv[1:])
        except airgap.AirgapError as e:
            print("airgap: %s" % e, file=sys.stderr)
            return 2
    probe = airgap.probes()
    if profile == "egress-blocked" and not probe["denied"]:
        print("airgap: the egress-blocked profile was requested, but the kernel does not deny network to this "
              "process; no engine was started.\n  probes: %s" % json.dumps(probe["probes"]), file=sys.stderr)
        return 2

    epoch, run_dir = new_run_dir(Path(args.run_root).expanduser())
    guard = airgap.load_guard(run_dir / "egress.jsonl")
    launcher = airgap.Launcher(run_dir, profile)
    cas = Cas(run_dir / "cas")
    graph = baseline_graph(args.model)
    journal = Journal(run_dir / "journal.jsonl", epoch, graph.cid)
    ctx = Ctx(run_dir=run_dir, cas=cas, journal=journal, ledger=ReviewLedger(cas), launcher=launcher)
    policy = airgap.policy(profile)
    journal.emit("run-admitted", refs=[graph.cid], graph=graph.to_json(), mode="live", modelLane=args.model,
                 airgap={"profile": profile, "policy": policy, "probes": probe})
    (run_dir / "graph.json").write_text(json.dumps(graph.to_json(), indent=2), encoding="utf-8")
    print("run %s | definition %s… | %s" % (epoch, graph.cid[:19], run_dir))
    print("  airgap %s | model lane %s" % (profile, args.model))
    if profile == "egress-blocked":
        ctx.check("AIRGAP", "the kernel refuses this process tree TCP, UDP, name resolution and bind",
                  probe["denied"], probe["probes"], "every probe denied")
        for label, outcome in probe["probes"].items():
            print("    probe %-36s %s" % (label, outcome))

    wanted = list(USE_CASES) if args.scenario == "all" else REQUIRES[args.scenario] + [args.scenario]
    results = {}
    for name in wanted:
        fn = USE_CASES[name]
        journal.emit("usecase-start", usecase=name)
        t0 = time.time()
        try:
            if name == "uc4":
                results[name] = fn(ctx, model=args.model,
                                   bundle=Path(args.model_bundle).expanduser() if args.model_bundle else None)
            else:
                results[name] = fn(ctx)
        except Exception as e:      # a failed use case is a failed check with its reason; later ones still run
            journal.emit("usecase-failed", usecase=name, error="%s: %s" % (type(e).__name__, e))
            ctx.check(name.upper(), "use case completed without exception", False,
                      "%s: %s" % (type(e).__name__, e), "no exception")
            results[name] = {"error": str(e)}
        journal.emit("usecase-end", usecase=name, seconds=round(time.time() - t0, 3))
        print("  %s: %s" % (name, json.dumps(results[name], default=str)[:240]))

    articulation_cid = write_articulation(ctx)
    egress = close_airgap(ctx, launcher, guard, profile, args.model, graph)

    # Rescaffold (Act four): config change -> NEW definition identity; old evidence historical.
    child = graph.with_config("panel-curation", seats=3, depth=2, workset=32)
    journal.emit("rescaffold", refs=[child.cid], parent=graph.cid, child=child.cid,
                 changed={"seats": [5, 3], "depth": [4, 2], "workset": [64, 32]},
                 note="old evidence remains historical; it cannot certify the new graph")
    ctx.check("SCAFFOLD", "config change produces a new definition identity", child.cid != graph.cid,
              child.cid[:19], "different CID")
    (run_dir / "graph-child.json").write_text(json.dumps(child.to_json(), indent=2), encoding="utf-8")

    rows = inventory.build(ctx.state, args.model)
    (run_dir / "inventory.json").write_text(json.dumps(
        {"scope": "what this run read, with digests; not a sealed bundle", "entries": rows}, indent=1), encoding="utf-8")
    journal.emit("inventory-written", refs=[cas.put_json(rows)], entries=len(rows))

    failed = [c for c in ctx.checks if not c["ok"]]
    report = {"epoch": epoch, "definitionCid": graph.cid, "childDefinitionCid": child.cid,
              "modelLane": args.model, "mode": "live", "synthetic": False,
              "airgap": {"profile": profile, "policy": policy, "probes": probe, **egress},
              "articulationCid": articulation_cid, "inventoryEntries": len(rows),
              "results": results, "checks": ctx.checks,
              "passed": len(ctx.checks) - len(failed), "failed": len(failed)}
    (run_dir / "report.json").write_text(json.dumps(report, indent=2, default=str), encoding="utf-8")
    journal.emit("run-complete", refs=[], passed=report["passed"], failed=report["failed"])
    journal.close()

    s = egress["summary"]
    print("  egress: %d engine processes, %d ledgers, %d external attempts, %d loopback refused%s"
          % (s["processes"], s["ledgersReported"], s["externalAttempts"] + egress["harness"]["external"],
             s["loopbackAttempts"] + egress["harness"]["loopback"],
             "; kernel denial confirmed in every ledger" if profile == "egress-blocked" and s["kernelDeniedEverywhere"] else ""))
    for r in s["refused"]:
        print("    refused in %s: %s %s by %s" % (r["process"], r["op"], r["to"], r["by"]))
    print("\n%d checks passed, %d failed" % (report["passed"], report["failed"]))
    for c in failed:
        print("  FAIL [%s] %s: observed=%r expected=%r" % (c["uc"], c["name"], c["observed"], c["expected"]))
    print("report:       %s" % (run_dir / "report.json"))
    if articulation_cid:
        print("articulation: %s" % (run_dir / "articulation.txt"))
    print("replay:       python3 %s replay %s" % (Path(__file__).name, run_dir))
    return 1 if failed else 0


def replay(run_dir: Path) -> int:
    journal_path = run_dir / "journal.jsonl"
    if not journal_path.exists():
        print("no journal at %s" % journal_path, file=sys.stderr)
        return 1
    print("REPLAY (no engines, no effects): %s" % run_dir)
    keys = ("usecase", "stage", "status", "reason", "name", "ok", "passed", "failed", "frames", "operators",
            "sequences", "proposals", "suspended", "parent", "child", "modelLane", "entries")
    n = 0
    for event in Journal.read(journal_path):
        n += 1
        refs = ",".join(r[7:15] for r in event["refs"])
        extra = {k: v for k, v in event["payload"].items() if k in keys}
        if isinstance(extra.get("operators"), list):
            extra["operators"] = len(extra["operators"])
        print("  %3d %-22s %-26s %s" % (event["seq"], event["kind"], refs, json.dumps(extra, default=str)[:120]))
    print("%d events replayed; nothing executed." % n)
    return 0


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)
    r = sub.add_parser("run")
    r.add_argument("--scenario", default="all", choices=["all"] + list(USE_CASES))
    r.add_argument("--model", default="q4bert", choices=["q4bert", "none", "jeva"],
                   help="the one optional model lane; q4bert is the 4-bit BERT encoder (default)")
    r.add_argument("--model-bundle", default=None, help="bundle directory for the selected model lane")
    r.add_argument("--airgap", default="egress-blocked", choices=list(airgap.PROFILES))
    r.add_argument("--run-root", default=str(DEFAULT_RUN_ROOT))
    p = sub.add_parser("replay")
    p.add_argument("run_dir", type=Path)
    args = ap.parse_args()
    return run(args) if args.cmd == "run" else replay(args.run_dir)


if __name__ == "__main__":
    sys.exit(main())
