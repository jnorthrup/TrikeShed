"""Explicit review, generation-fenced productions, revocation (I04, I12, I13).

Admission is a named, scoped operator action on a candidate — never automatic.
A production carries its exact dependency set (source, review, engine identity)
and a generation. Withdrawal of any dependency suspends the generation; a
queued stale activation cannot dispatch; the next query returns to analysis.
Repeated reuse never increases empirical confidence (I06).
"""
from __future__ import annotations

from .cas import Cas


class ReviewLedger:
    def __init__(self, cas: Cas):
        self.cas = cas
        self.reviews = {}       # reviewCid -> review object
        self.productions = {}   # productionId -> production
        self.withdrawn = set()  # dependency cids withdrawn

    def admit(self, *, candidate: dict, scope: str, reviewer: str, context: str = "asserted") -> dict:
        if context not in ("asserted", "hypothetical", "fictional", "derived"):
            raise ValueError("review requires an explicit context")
        review = {"object": candidate, "scope": scope, "reviewer": reviewer,
                  "context": context, "decision": "admitted",
                  "note": "approval of reading, not of truth or production use"}
        cid = self.cas.put_json(review)
        self.reviews[cid] = review
        return {"reviewCid": cid, **review}

    def withdraw(self, dependency_cid: str, reason: str) -> dict:
        self.withdrawn.add(dependency_cid)
        event = {"withdrawn": dependency_cid, "reason": reason}
        event["receiptCid"] = self.cas.put_json(event)
        suspended = []
        for prod in self.productions.values():
            if prod["status"] == "active" and dependency_cid in prod["dependencies"]:
                prod["status"] = "suspended"
                prod["suspensionReason"] = reason
                suspended.append(prod["id"])
        return {**event, "suspendedProductions": suspended}

    def propose_production(self, *, pid: str, condition: dict, action: dict,
                           dependencies: list, reference_result: dict,
                           guard: str = "all dependencies current") -> dict:
        prod = {"id": pid, "generation": 1, "condition": condition, "action": action,
                "dependencies": list(dependencies), "referenceResult": reference_result,
                "guard": guard, "status": "shadow", "activations": 0, "savedWork": []}
        self.productions[pid] = prod
        return prod

    def activate(self, pid: str) -> dict:
        prod = self.productions[pid]
        if prod["status"] != "shadow":
            raise ValueError(f"production {pid} is {prod['status']}, not shadow")
        stale = [d for d in prod["dependencies"] if d in self.withdrawn]
        if stale:
            raise ValueError(f"cannot activate: withdrawn dependencies {stale}")
        prod["status"] = "active"
        return prod

    def try_activate(self, pid: str, *, generation: int, query: dict) -> dict:
        """Fenced dispatch: stale generations are refused, never silently run."""
        prod = self.productions[pid]
        if generation != prod["generation"]:
            return {"dispatched": False, "reason": "stale generation", "returnsToAnalysis": True}
        if prod["status"] != "active":
            return {"dispatched": False, "reason": f"production {prod['status']}",
                    "returnsToAnalysis": True}
        stale = [d for d in prod["dependencies"] if d in self.withdrawn]
        if stale:
            prod["status"] = "suspended"
            return {"dispatched": False, "reason": "withdrawn dependency", "withdrawn": stale,
                    "returnsToAnalysis": True}
        prod["activations"] += 1
        effect = {"production": pid, "generation": generation, "query": query,
                  "result": prod["referenceResult"], "effect": "local-derived-graph-publication"}
        effect["receiptCid"] = self.cas.put_json(effect)
        prod["savedWork"].append(effect["receiptCid"])
        return {"dispatched": True, "effectReceiptCid": effect["receiptCid"],
                "note": "reuse does not increase empirical confidence"}
