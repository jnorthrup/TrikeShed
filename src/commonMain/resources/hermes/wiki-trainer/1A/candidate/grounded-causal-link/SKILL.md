# Grounded causal link

Translate between NLPCore and NARS through BrainClient/ModelMux while preserving an immutable Hermes transcript CID and its CoreNLP sentence/dependency record.

1. Route NLPCore→NARS and NARS→NLPCore translations through BrainClient and ModelMux.
2. Recompute the transcript SHA-256 and refuse a CID mismatch.
3. Resolve the exact sentence and indexed dependency tuples from the CoreNLP record.
4. Refuse a causal proposal when the predicate is absent.
5. Resolve the subject and object through dependencies sharing the same predicate index.
6. Refuse when the proposed direction differs from the dependency direction.
7. Mint NARS evidence and budget only after every deterministic check passes.
8. On reverse translation, project the admitted belief back to its original transcript CID, sentence, span, and dependency indices.

Do not infer causation from co-occurrence. BrainClient/ModelMux translates; it does not authorize an edge. Do not let generated prose replace a dependency fact.
