# R3f runtime update

This overlay requires the exact R4f Linux x86-64 runtime followed by the R3e final runtime update named and hashed in UPDATE.json. Verify both archives and extract them, in that order, into a fresh installation directory. Then extract this archive into the same directory. All use airgap-document-rootfs/. Do not overwrite a running installation or writable state.

Verify sha256sum --check SHA256SUMS and the complete membership/modes in package-identity.json with the retained verifier. Retain the documented read-only root, separate writable state, UTF-8 locale and loopback-only namespace launch. Host kernel, bwrap and browser remain external inputs.

Only the web jar and manifests change. Wire pointer editing is delegated to one handler under the existing page owner, which removes it on close. Retained aggregate output maps are read within 1 MiB and checked for byte count, SHA-256, and current canvas ownership before projection. This fixes wire editing after awaited page shutdown; it does not establish shutdown correctness for every other UI control, large-graph frame-rate acceptance or absence of all memory leaks. The baseline diagnostic did not show listener growth across 100 redraws after forced GC.
