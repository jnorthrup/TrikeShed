# Grounded causal link

## Failure pattern

A lexical gate can find a subject phrase, object phrase, and causal word in the same evidence span while still accepting the wrong direction. Co-occurrence can also be mislabeled as causation when no causal predicate exists.

## Grounding rule

BrainClient is the translation client and ModelMux routes its model seat. Together they translate NLPCore structures into NARS proposals and project admitted NARS beliefs back toward NLPCore evidence coordinates. They do not decide whether evidence exists.

Admit a causal link only when all of these are true:

1. The exact transcript bytes hash to the cited evidence CID.
2. The exact source span exists in those bytes.
3. CoreNLP emitted indexed dependencies for that span.
4. The proposed subject resolves through the predicate's subject dependency.
5. The proposed object resolves through the same predicate's object or consequent dependency.
6. The edge direction matches those dependency indices.
7. Only then may NARS receive positive evidence and a budget.

An LLM routed by BrainClient/ModelMux may translate or propose a link, but it supplies no evidence merely by proposing it. The reverse translation must return the original CID, sentence, span, and dependency coordinates rather than generate a replacement explanation.

## Trainer provenance

- `sha256:7a72b0428e73ba3ce0e2b26fba3eb53f13aafc89edf39462203feb5878eea03b`
- `sha256:3b33d24932fe2c531171abfb1713cda0bd28ccf849e19c275e3cb29f83a9cc8d`
- `sha256:867c7137aa47e2aaeb42006015bd55df06e3671a04406eb8dde1ae75168923a9`
- `sha256:e8eff09b94145caa24f50c56410202b3ad38ed041ed385a7106d5e87e7818e5f`
