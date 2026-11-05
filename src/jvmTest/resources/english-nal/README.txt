Recorded parser observations, not fresh-parser or legal-gold fixtures.

Exact materialized source and canonical inspection bytes from the genuine local
18-document baseline published as docs/airgap/evidence/english-guard-baseline-v77-20261105.
The .cbor.b64 files are Base64 text copies; JVM tests decode them without changing
bytes, assert the original inspection CIDs, and use the production source reader.
No model/parser is installed or executed by these tests.

Producer source0219223a3846887fe283b93598942de332214cd2 / main JAR SHA256
d0a8133b10e4b18e1bd4076c1440688ad94a2eeb2bbcdefe743f4759ace75636.
Actual local CoreNLP4.6.0 syntax-v1 profile and full token-index+copy-count/1
metadata remain unchanged inside each canonical receipt.

s12 inspection sha256:b8d43e44fd90ed082538dee097f2eaad7f2eddc718baf5148afb5762da9e53f0
source sha256:2d357e82bb8d7c51254eb88f162eaa5ba1dca5d2049ebd75f8b2ed58a166f3ac
105 exact UTF8 bytes; no final newline; full range [0,105).

G01 (guard) inspection sha256:9ee6a4c328fe5bff1dd898019e51ff862032992985f4a3c3270c100f4e4e27c2
source sha256:33739ec22bf33c7433aee82d0325108428f02a922a840c664746198b7b8fa4b0
39 exact UTF8 bytes; no final newline; full range [0,39).
The producer returned200 and retained this valid receipt before the first
external observer process failed a wrong runner-registration assertion. That
post-parse observer failure remains preserved in the published baseline; this
fixture does not convert the failed process to green.

Both actual parses had zero copied vertices: actual copy handling NOT_EXERCISED.
The tests supply controlled current-source rows reconstructed from the receipts
and mutate those rows for currentness controls. Those are explicit test fixtures,
not a new authentication of the original producer or a current project claim.
