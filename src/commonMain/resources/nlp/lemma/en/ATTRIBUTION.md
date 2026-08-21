# English lemma dictionary — provenance and attribution

`observations.tsv` in this directory is **derived data**: it is produced by running a reference
lemmatizer over a corpus and recording, for each token, its surface form, the lemmas of its immediate
neighbors, and the lemma the reference assigned. TrikeShed's `FunnelLemmatizer` freezes this file into
frozen funnel generations; no reference lemmatizer runs at TrikeShed runtime, on any target.

## Reference lemmatizer

- **Stanford CoreNLP** (`edu.stanford.nlp:stanford-corenlp`, `lemma` annotator, English).
  Manning, Surdeanu, Bauer, Finkel, Bethard, McClosky. *The Stanford CoreNLP Natural Language Processing
  Toolkit.* ACL 2014 System Demonstrations. Licensed **GPL v3 or later**.
- CoreNLP's English lemmatizer is **Morpha** — a finite-state morphological analyser compiled into a
  generated lexer class, not a word list. Minnen, Carroll, Pearce. *Applied morphological processing of
  English.* Natural Language Engineering 7(3), 2001. There is therefore no Stanford dictionary file to
  copy; this TSV is the *output* of that analyser over the corpus named in `MANIFEST.txt`.

CoreNLP is used only as a build-time tool in the separate `bench/lemma` Gradle build
(`extractDictionary` task). It is not a dependency of TrikeShed's main build. The generated TSV is the
output of a GPL program, which the GPL does not itself cover; the corpus it was run over is listed in
`MANIFEST.txt` with each source's own terms.

## Corpus

See `MANIFEST.txt` (written by the extractor): source paths, token counts, CoreNLP version, date.
Default corpus is TrikeShed's own documentation tree plus `/usr/share/dict/words` where present
(macOS `web2`, public domain).

## Funnel index

`FunnelHashIndex` implements the funnel-hashing geometry of Krapivin, Farach-Colton, Kuszmaul,
*Optimal Bounds for Open Addressing Without Reordering*, arXiv:2501.02305 (2025). Code-level provenance
of that file is tracked separately.
