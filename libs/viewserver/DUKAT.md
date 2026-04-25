dukat usage

This file explains how to convert TypeScript declaration files (.d.ts) into Kotlin externs for the viewserver module using dukat.

Prerequisites

- Node (for npx) is required to run dukat, but keep this step isolated and audited.
- Ensure ts declarations have been generated (see generateDts task).

Generating externs (recommended workflow)

1. Emit declarations from user JS/TS:

   ./gradlew :libs:viewserver:generateDts

   This runs `npx tsc -p tsconfig.json` and writes declarations per tsconfig (default: build/dts).

2. Convert .d.ts to Kotlin externs:

   ./gradlew :libs:viewserver:generateKotlinExterns

   This runs `npx dukat build/dts -o build/dukat` and writes Kotlin files into build/dukat.

3. The generated Kotlin files are added to the jsMain source set automatically; they will be compiled with the viewserver.

Security notes

- Run the tsc/dukat steps in an isolated build agent or container; avoid running untrusted user code on developer machines.
- Prefer hand-review or CI-based scanning of generated externs before trusting them.
- The runtime viewserver should not use kotlin.js.eval; prefer compiled externs or hand-ported Kotlin implementations.

Next steps

- Optionally integrate dukat invocation via a verified binary instead of npx to avoid extra Node dependency.
- Implement a small CI job that runs generateDts + generateKotlinExterns inside a sandbox and publishes the generated sources for review.
