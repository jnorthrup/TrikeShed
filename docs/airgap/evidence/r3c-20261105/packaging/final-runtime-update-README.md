# R3c runtime update

This archive is an overlay, not a standalone runtime. Retain the exact R4f Linux x86-64 runtime named and hashed in UPDATE.json. Verify that base archive's SHA-256, then extract it into a fresh installation directory. Extract this update into that same directory. Both archives use airgap-document-rootfs/; the update replaces only the paths enumerated in UPDATE.json. Do not overlay a running installation or overwrite writable runtime state.

Inside airgap-document-rootfs, verify `sha256sum --check SHA256SUMS` and the complete file/directory membership and modes against package-identity.json. Use the retained package verifier for the latter checks. Apply the recorded read-only filesystem, writable state, UTF-8 locale and isolated network launch from the runbook. The host kernel, bwrap and browser remain external inputs.

The new browser bundle adds continuous display speed, presentation-only pause/resume and bounded pinned transfer inspection. The combined build includes D3M exact-role NARS selection, null wired-identity refusal and checked native Horn proof projections. Sulong engine modules, native buffers and durable storage code retain their existing contracts. This update does not establish dense recursive visualization, machine power-loss durability or full vendor/native rebuild provenance.
