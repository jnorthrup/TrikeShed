# R3c source update

Retain both source archives named and hashed in source-update.json. Begin with the R4e source companion and apply the R4f source update according to its instructions. That yields source commit 4c00eccd7d2e8ce65ade1b23734c9aa1a018f0ab and its unchanged dependency source inputs. Apply this archive's trikeshed.patch with `git apply --binary`. The patch includes every tracked change through targetSourceCommit; a temporary Git-index application reproduced targetTree exactly. No dependency source is replaced by this UI change.

This archive is not standalone and does not include later evidence-publication commits. Complete vendor JDK patches and native-resource source rebuild provenance remain open. Use the source/build/runtime identities in the release verification packet to distinguish browser changes from the unchanged JVM/Sulong runtime.
