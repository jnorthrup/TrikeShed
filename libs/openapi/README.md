# libs/openapi

OpenAPI 3.x spec parser + Kotlin code generator. Reads YAML or JSON OpenAPI
documents, resolves all `$ref` pointers, performs gap analysis, and emits
TrikeShed-idiomatic Kotlin sources (Keys, Elements, Api interface, Models,
ServerAdapter, SupervisorJobs).

## What It Is (Mechanically)

A **pure-function code generation pipeline** with no side effects until
`GenerateSources.main()` writes files to disk.  The pipeline is:

```
spec text (YAML|JSON)
  -> OpenApiRawParser.parse()         // produces OpenApiRawDocument (JsonMap tree)
  -> .resolve()                       // inlines $ref, resolves schemas/params/responses
  -> .gapAnalysis()                   // tokenises and reports missing operationIds, etc.
  -> renderAllClientSources()         // Keys, Elements, Api, Models, SupervisorJobs
  -> renderAllServerSources()         // ServerAdapter, ServerMessage, Keys, Elements
```

All rendering functions are top-level pure functions that return `Map<String, String>`
(relative path -> file content).

## Source Layout

### commonMain (Kotlin Multiplatform)

| File | Role |
|------|------|
| `OpenApiRawParser.kt` | `object OpenApiRawParser` — parses YAML/JSON into `OpenApiRawDocument`. Defines `OpenApiToken`, `OpenApiGap`, `OpenApiGapAnalysis`, `OpenApiRawOperation`. Includes tokeniser and gap analysis. |
| `OpenApiKotlinGenerator.kt` | Shared Kotlin rendering helpers: `toPascalCase`, `toCamelCase`, `toKotlinType`, `derivePackageRoot`, `deriveDisplayName`, operation-name helpers, `generatedBanner`. |
| `OpenApiClientGenerator.kt` | `ClientGenConfig` + `renderAllClientSources()` — generates Keys, Elements, Api interface + default impl, Models, SupervisorJobs, infrastructure (HttpMethod, GeneratedRequest). |
| `OpenApiServerGenerator.kt` | `ServerGenConfig` + `renderAllServerSources()` — generates ServerAdapter, ServerMessage, server-side Keys, server-side Elements. |
| `OpenApiReactorModel.kt` | Domain model for resolved docs: `ResolvedSchema` (sealed), `ResolvedOperation`, `ResolvedParameter`, `ResolvedResponse`, `ContentType`, `SecurityRequirement`, `ContextBinding`, `TrikeshedContext`, `ResolvedOpenApiDocument`. |
| `OpenApiReactorResolver.kt` | Reference resolution (`resolveAllRefs`, `walkAndResolve`), schema/parameter/response/content/security resolvers, `resolveOperation`, full `OpenApiRawDocument.resolve()`, `parseTrikeshedContext`. |
| `OpenApiCallPipeline.kt` | Coroutine fan-out/fan-in pipelines: `speculativeParseBurndown`, `speculativeGapBurndown`, `speculativeSignalBurndown`. Uses `coroutineScope` + `Channel` + `constructiveSupervisorCall` for structured parallelism. |

### jvmMain

| File | Role |
|------|------|
| `GenerateSources.kt` | JVM CLI entry point. `main(args)` parses `--spec`, `--target`, `--output`, `--sides` flags, runs the full pipeline, writes generated files to disk. |

### Tests (commonTest + jvmTest)

| Test | What it exercises |
|------|-------------------|
| `OpenApiRawParserTest` (commonTest) | JSON parsing, ref extraction, gap analysis on incomplete specs. |
| `OpenApiCallPipelineTest` (commonTest) | `speculativeParseBurndown` fan-out/fan-in, fail-fast on invalid payload, gap analysis channelisation. |
| `OpenApiPipelineTddTest` (commonTest) | Skeleton TDD contract for pipeline stages (placeholder assertions). |
| `OpenApiSignalBurndownTest` (jvmTest) | `speculativeSignalBurndown` with real Kraken + CMC YAML files; contour signal extraction. |
| `KrakenRestOpenApiSpecTest` (jvmTest) | Validates Kraken Spot REST API spec file surface. |
| `KrakenFamilyOpenApiSpecTest` (jvmTest) | Validates Kraken Custody/Embed/Futures spec files. |
| `RobinhoodOpenApiSpecTest` (jvmTest) | Validates Robinhood Crypto Trading API spec surface. |
| `GuideContourSpecTest` (jvmTest) | Validates guide-contour OpenAPI specs (Kraken global-intro, CMC endpoint-overview). |
| `YamlParserTest` / `YamlDebugTest` (jvmTest) | YamlParser integration against real spec files. |

## Key/Element/Reactor Status

- **Keys**: Generated `object Keys` contains `AsyncContextKey<E>` per `x-trikeshed-context` binding. Currently placeholder when no bindings declared.
- **Elements**: Generated `object Elements` contains `suspend fun` factory methods per binding. Currently placeholder.
- **Reactor bindings**: Defined via `x-trikeshed-context` YAML extension (client/server sections with name/key/element/open FQNs). Parsed by `parseTrikeshedContext`.
- **SupervisorJobs**: Generated per operation marked `x-trikeshed-supervisor: true`. Rendered as `SupervisorJob(parent)` factories.
- **No runtime state** in the generator itself — all rendering is pure.

## Dependencies

- `borg.trikeshed.parse.json.JsonParser` — JSON reification
- `borg.trikeshed.parse.yaml` — YAML parsing
- `borg.trikeshed.lib` — `toSeries()`, `Series<T>`
- `kotlinx.coroutines` — Channel, coroutineScope, select, SupervisorJob
- JVM-only: `java.io.File` for GenerateSources CLI

## Naming Conventions

- Package root derived from spec title: `kraken` -> `borg.trikeshed.krak`, `coinmarketcap`/`cmc` -> `borg.trikeshed.cmc`, `robinhood` -> `borg.trikeshed.rhood`
- All generated files carry a banner: "Generated from ... by ./gradlew ... — must be regenerated, not edited by hand."
