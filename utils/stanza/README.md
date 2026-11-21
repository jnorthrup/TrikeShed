# utils/stanza: legal frames → SUMO checks → taxonomy gaps

A deterministic pass over legal text with no generative model. Stanza's UD parser (tokenize, mwt, pos,
lemma, depparse, ner) reads each sentence; hand-written rules over the dependency tree pull out frames:
modal norms, grants and authorizations, assent, definitions, copular claims, norm predicates (“is
required”, “reserves the right”), and property relations and events. Each frame's roles are typed
lexically and checked against the argument types SUMO declares for the relation the frame needs.
Findings are taxonomy observations with file:line provenance, never legal conclusions.

Quotes are always cut from the source by offset (code points, plus UTF-16 for browsers). Lemmas,
truecasing and parse repairs are annotations for matching; no statement is rewritten.

## Setup (once, online)

    ./setup.sh            # own venv (py3.12, stanza 1.15.0, nltk 3.9.2), en models, WordNet,
                          # WordNetMappings30 verb/adj files at the pinned SUMO ref

The SUMO corpus is the repo's pinned full corpus (`utils/sumo/sumo-full-corpus.pins`, built by
`./gradlew fetchSumoFullCorpus`). Runs are offline after setup.

## Run

    export TRIKESHED_SUMO_FULL=<build>/generated/sumo-full/resources/sumo/full
    export TRIKESHED_WN_NOUN=<build>/generated/sumo/resources/sumo/WordNetMappings/WordNetMappings30-noun.txt
    python run.py manifest.json out [--overlay proposals/legal-agreements.kif] [--retract "subclass A B"]
    python report.py out [--traces traces.json]     # out/report.md
    python show.py out --findings-only
    python -m unittest discover -s tests            # no Stanza or SUMO needed

A manifest lists sources: `id`, `title`, `text` (path), `layout` (`lines` for HTML-derived text,
`paragraphs` otherwise), optional `begin`/`end` code-point range, `kind` (`statute` turns indefinite
copular sentences into definitions other sources can use), `origin`. Keep private inputs and run
outputs outside the repository.

Outputs: `frames.jsonl` (frame, typings, checks, findings per frame), `gaps.json` (findings grouped
by kind and key with verbatim examples), `summary.json` (parser and model hashes, SUMO pins, mapping
hashes, counts). With `--overlay`, the same frames are typed again against SUMO plus the overlay
(`*.overlay.*`), so a proposal's effect is measured on the same evidence.

## Operator logic

    python operators.py manifest.json out           # after run.py on the same manifest and out dir

Reads the parse `run.py` cached (no parser is loaded) and writes the sentence's logical skeleton as the
tree states it: a `mark` under an `advcl` (if, unless, because, before), a causal verb with its oblique
(`result in`, `arise out of`, `lead to`), a fixed preposition (`as a result of`, `due to`, `in the event
of`), a modal `aux` with the reading the frame inference selected, a norm predicate, a negator, a
coordinator between predicates. Each operator names its construction and the edges it read, its cue and
operands as source quotes, a SUMO form, what stays unresolved, and two weights:

- `deterministic` = base × resolved / required roles. Base is 0.9 for a causal verb that is the clause's
  own predicate, 0.8 for a cue with one standing reading, 0.5 for one with two (`may`, `or`, `when`,
  `where`, `since`, bare `to`). A role filled by inheritance, ellipsis or an anaphor is unresolved.
- `ontological` = SUMO checks entailed / total for the operator's relation on these operands (`causes`
  wants two Processes, `holdsObligation` a CognitiveAgent), on the first mapped sense. `anySense` is the
  ratio when another mapped sense of the same word may stand in; the pair shows how much of a low weight
  is sense choice and how much is the ontology not fitting.

Outputs: `operators.jsonl`, `logic.jsonl` (one composed form per sentence; scope is a stated convention),
`masks.jsonl` (one masked-cue request per operator whose cue has a lexical class, for `utils/q4bert`),
`operators.summary.json`. Operators are candidates: none is admitted, and none establishes legal force.

This step loads no model (its input is the parser's tree), and the same inputs give the same bytes in a
new process. That last property needed
a fix in `sumo.disjoint_witness`, which walked two sets in hash order and so named a different witness
pair from run to run; it now walks them sorted, and a test runs it under eight hash seeds.

## Typing order

1. the document's own party aliases and pronouns (“Coinbase, Inc. (“Coinbase”, “we”, “us”)”);
2. Stanza NER for proper names, refined by what the text says of legal form (Inc., N.A., bank);
3. definitions in the corpus (“X means …”, statute “A thing in action is a right …”), typed by genus;
4. SUMO's own English `termFormat` phrases (multiword only);
5. WordNet 3.0 through the SUMO WordNet mappings, surface form before lemma (“assets” ≠ “asset”),
   with `LEGAL_SENSES` as an explicit, reviewable sense override for the contract register.

## Grammar rules worth knowing

- Modal readings depend on the subject: agentive subjects get deontic readings; a party's “will” is
  a commitment, anyone else's a prediction; a non-agent's “must” is a requirement on a state of
  affairs (SUMO `modalAttribute`), and a non-agent's “shall have effect / remain / govern” is a
  constitutive rule (no SUMO relation).
- Agentless passives and passive complements leave the bearer unexpressed rather than guessing.
- Sentences in capitals are truecased for the parser only (unigram table from the same document).
- “IN NO EVENT SHALL X … BE LIABLE”: the negative inversion is repaired after parsing and recorded
  in `parseRepairs` on each frame.
