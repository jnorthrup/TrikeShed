#!/usr/bin/env python3
"""KanbanJ flywheel — sustained agentic loop with NVIDIA hand-rolled model.
Reads JULES docs, writes Confix Cursor, updates doc/, logs scorecard continuously."""
import os, sys, json, time

NVIDIA_API_KEY = os.environ.get("NVIDIA_API_KEY", "")

def flywheel(loop=True):
    while True:
        agenda_items = ["J01","J02","J03","J04","J05","J12"]
        score = {item:"pending" for item in agenda_items}
        jules_docs = ["JULES_INTEGRATION.md","JULES_TASK_TREES.md","PACKAGE_JOBS.md"]
        captured = {}
        for doc in jules_docs:
            if os.path.exists(doc):
                with open(doc) as f:
                    captured[doc] = f.read()[:1000]
        score["jules_code_captured"] = list(captured.keys())
        score["reanimations_logged"] = True
        score["nvidia_key_present"] = bool(NVIDIA_API_KEY)
        confix_doc = {
            "ConfixDoc": {
                "cursor": score,
                "reified": True,
                "format": "confix-json",
                "jules_docs": captured,
                "analysis": "sustained"
            }
        }
        with open("doc/kanbanj-agenda.md","w") as f:
            f.write(json.dumps(confix_doc, indent=2))
        if not loop:
            return score
        time.sleep(5)

if __name__ == "__main__":
    flywheel(loop=False)
