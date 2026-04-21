# htx-client OpenAPI generation

The htx-client generated surface for htx-general comes from one authoritative OpenAPI document:

- `libs/server/openapi/htx-general.openapi.yaml`

Regenerate the checked-in client artifacts with:

- `./gradlew -p libs/htx-client openApiGenerateHtxGeneralClient`

Verify that the checked-in generated sources still match the authoritative contract with:

- `./gradlew -p libs/htx-client verifyHtxGeneralClientGeneratedSources`

Repository policy:

- generated Kotlin sources live only under `libs/htx-client/src/generated/kotlin`
- the generated outputs are checked in for review and must be committed after regeneration
- verification is non-mutating: `verifyHtxGeneralClientGeneratedSources` checks for drift without rewriting files
- this repo does not use a verify-only policy for these generated files
- do not hand-edit generated files; regenerate them from the OpenAPI spec instead
- compatibility with the htx-general server is exercised via `./gradlew -p libs/server jvmTest --tests borg.trikeshed.server.HtxGeneralClientServerCompatibilityTest`

CI/local validation targets:

- codegen drift gate: `./gradlew -p libs/htx-client verifyHtxGeneralClientGeneratedSources`
- generated client contract check: `./gradlew -p libs/htx-client jvmTest --tests borg.trikeshed.htx.client.GeneratedHtxGeneralClientTest`
- client/server compatibility check: `./gradlew -p libs/server jvmTest --tests borg.trikeshed.server.HtxGeneralClientServerCompatibilityTest.generatedClientRoundTripMatchesOpenApiContract`
- route drift check: `./gradlew -p libs/server jvmTest --tests borg.trikeshed.server.HtxGeneralClientServerCompatibilityTest.adapterRejectsPathDriftWithConcreteFailureSignals`
- reviewable contract suite: `./gradlew -p libs/server jvmTest --tests borg.trikeshed.server.HtxGeneralOpenApiContractTest`
