# Lemma benchmark — CoreNLP live vs FunnelLemmatizer (frozen from observations.tsv)

freeze: 2185 ms, observations=181462, vocabulary=126344, linkedContexts=187364

| corpus | scale | tokens | corenlp ms | funnel ms | speedup | agreement | tokens lemmatized | skipped sents | skipped paras |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|
| docs | sentence | 62441 | 22891 | 309 | 74.1x | 99.96% | 39941 | 1029 | 1029 |
| docs | paragraph | 62441 | 20877 | 222 | 94.0x | 99.96% | 39895 | 1048 | 371 |
| docs | document | 62441 | 21529 | 224 | 96.1x | 99.96% | 39895 | 1048 | 371 |
| synthetic-r0 | sentence | 13065 | 7116 | 108 | 65.9x | 96.97% | 13065 | 0 | 0 |
| synthetic-r0 | paragraph | 13065 | 6744 | 102 | 66.1x | 96.97% | 13065 | 0 | 0 |
| synthetic-r0 | document | 13065 | 6794 | 105 | 64.7x | 96.97% | 13065 | 0 | 0 |
| synthetic-r50 | sentence | 12970 | 8026 | 39 | 205.8x | 96.99% | 3208 | 753 | 753 |
| synthetic-r50 | paragraph | 12970 | 7609 | 37 | 205.6x | 96.99% | 3208 | 753 | 102 |
| synthetic-r50 | document | 12970 | 7751 | 42 | 184.5x | 96.99% | 3208 | 753 | 102 |
| synthetic-r90 | sentence | 12039 | 10785 | 13 | 829.6x | 98.27% | 145 | 1054 | 1054 |
| synthetic-r90 | paragraph | 12039 | 10182 | 12 | 848.5x | 98.27% | 145 | 1054 | 182 |
| synthetic-r90 | document | 12039 | 10287 | 12 | 857.3x | 98.27% | 145 | 1054 | 182 |
