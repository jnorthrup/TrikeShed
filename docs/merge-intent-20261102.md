# Local three-way integration intent — 2026-11-02

The owner requested a local, three-way integration of all unmerged cloud and
local branches, with conflicts retained until the clean merges were complete.
The earlier `d2e3ca0ff` history-only merge did not perform those three-way
merges. This integration starts from its first parent `62eb34b08`, performs
the clean merges, and then resolves the deferred branches below. The branch tips
remain parents of the individual merge commits so both inputs are inspectable.

- `codex/documents-pdf-links-20261103`: the same PDF link display code used
  256 and 4096 as its limit. The later integrated `877643774` aligns the limit
  with the inventory bound; retain 4096 and the other identity checks.
- `codex/documents-upload-20261103`: retain whole-window file and folder drop,
  selected-document preservation, and PDF cleanup on page hide. The upload
  explanation also retains the storage-only, no-approval, and separate
  TXT/MD retention intent from the other branch.
- `codex/jeva-layout-20261103`: both Jeva fixtures cover the same behavior;
  retain the repository collection literals. The manual-upload tests also
  retain the newer revision-bound selection and its additional coverage.
- `codex/native-horn-query-20261102`: retain the shared guide and the additional
  integrated-artifact evidence, including its stated verification limits.
- `codex/pdf-links-wire-20261103`: retain the later fix that removes the mux
  `Job` before composing request context, preserving request ownership.
- `codex/reviewed-taxonomy-20261102`: retain taxonomy authentication,
  POST-only routing and tests, together with the subsequently integrated Horn
  input/query routes, PDF routes, and their additional tests.
- `scratch-fold`: its `JvmProcessOperationsTest.kt` was byte-identical to the
  version deliberately removed by `75eda0bad`. Keep that recorded removal;
  the branch's other changes were incorporated by the three-way merge.

The Graal ingest error layout change is reapplied after these merges. The
separate local Hermes submodule checkout is not part of this integration.
