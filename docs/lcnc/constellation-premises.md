# Constellation premise inputs

`constellation.ask` uses only the optional `positiveFacts` and `negativeFacts`
JSON arrays as premise assumptions. Each item is an exact premise string, such
as `"rent be due"`; callers can inspect `pending` for the strings a query needs.
Positive and negative arrays are caller assumptions, not verified evidence.
Both signs for the same string produce `conflicted`. No sign produces `pending`;
an explicit positive blocks an `unless` norm, and an explicit negative makes
it eligible. An `if` norm has the opposite eligibility. Unconditioned norms do
not need a premise.

This changes the legacy `facts` text input: its extracted `parsedCandidates`
are shown for inspection and **do not activate conditions**. The parser can
misread negation and qualification. Facts serialized in joined books and model
suggestions are also withheld from applicability. To migrate an existing graph,
review the intended premise and wire a JSON array of exact strings to the
appropriate assumption port; no text is automatically converted to a negative.

The oracle interface has no explicit premise-array inputs and consequently
leaves its conditioned norms pending. Other legacy book summaries and cards may
still display stored facts; they are not authenticated premise evidence. A
query-dependent conditional result is not published as a global `normHeld`
assertion. Existing previously published assertions are not removed by this
change.
