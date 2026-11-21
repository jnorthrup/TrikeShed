"""LCNC airgap demonstrator: baseline curation vertical slice (D0/D1).

Implements the retained contracts of docs/lcnc/airgap-demonstrator for the baseline curation use cases:
immutable source retention, durable semantic journal, stage receipts, quotation/admission separation,
explicit review, generation-fenced productions with revocation, replay without effects, and a run that
denies network to its own process tree and keeps the evidence.

Real engines are driven, never simulated: utils/stanza (deterministic frames and operator logic with a
deterministic and an ontological weight) and utils/q4bert (a 4-bit BERT encoder that scores operator cues
and writes no text). No generative language model runs in the default profile. A stub engine exists only for unit
tests and is labeled.
"""

__version__ = "0.2.0"
