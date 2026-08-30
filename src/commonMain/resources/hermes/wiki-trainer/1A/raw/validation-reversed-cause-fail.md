---
kind: hermes-transcript
trainer-set: 1A
case: validation-reversed-cause-fail
split: validation
outcome: fail
---

user: Which direction does the record support?
assistant: The watcher reconciliation caused the daemon log write.
tool: dependency subject=watcher_reconciliation predicate=caused object=daemon_log_write
assistant: [fail] Reversing subject and object would hallucinate a different causal edge.
