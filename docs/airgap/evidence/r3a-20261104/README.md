# R3a evidence packet

`verification.json` names the exact accepted source, checks, runtime artifact
hashes and limits. `accepted` contains actual browser assertions, screenshots,
recordings, fixture sources, CAS bytes, network boundary and awaited cleanup.
The browser executes the production modules and native kernels. Its synthetic
JSON is an execution fixture, not a legal document.

`preliminary` retains two failed harness attempts without upgrading them to
final acceptance. Their identities are qualified in the manifest. `logs`
retains the initial invalid Gradle invocation, successful combined/final checks
and dependency staging. No failed attempt is erased by the final successful run.

CAS filenames are verified against their byte digests. Parsed HTTP response
objects are previews; only the CAS files are the exact bytes identified by CIDs.
`SHA256.json` covers this packet except itself. R3 and R4 remain open gates.
