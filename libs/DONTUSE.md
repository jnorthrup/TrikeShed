# libs/ DIRECTORY PERMANENTLY REMOVED — DO NOT RECREATE UNDER ANY CIRCUMSTANCES

**This directory exists ONLY to host this warning file.**  
No other files, no subdirectories, no Gradle subprojects, no Kotlin source — nothing else is allowed here.

---

## TRIKESHED IS ROOT-ONLY

- Single Kotlin Multiplatform project
- All source under `src/` (commonMain, jvmMain, linuxX64Main, wasmJsMain, jsMain, etc.)
- Single `build.gradle.kts` at repo root
- Single `settings.gradle.kts` at repo root (no `include(":libs:*")`)

---

## WHY libs/ WAS NUKED

| Problem | Impact |
|---------|--------|
| 50+ fragmented Gradle subprojects | Config cache failures, dependency cycles, version skew |
| 2000+ lines dynamic inclusion logic in `settings.gradle.kts` | Unmaintainable, flaky builds |
| Each subproject had own `build.gradle.kts` | Duplicate configs, conflicting plugins |
| Composite builds, subproject references | Broken IntelliJ sync, broken CI |

All useful code was **inlined into `src/`** as a single MPP source set. The rest was dead weight.

---

## IF YOU NEED THE OLD libs/ CODE

It exists **only** at the archive tag from when we first removed it:

```bash
# Tag: libs-archive-2026-07-13
# One-time checkout to /tmp for reference/porting ONLY:
git clone --branch libs-archive-2026-07-13 --depth 1 \
  https://github.com/jnorthrup/TrikeShed.git /tmp/libs

# Or from an existing local clone:
git worktree add /tmp/libs libs-archive-2026-07-13
```

**DO NOT:**
- ❌ Merge, subtree-merge, or subtree this back
- ❌ Copy whole directories back into the repo
- ❌ Reference `libs/` in any CI, docs, scripts, or tooling
- ❌ Create any file under `libs/` except this `DONTUSE.md`

**DO:**
- ✅ Port ONLY the specific files you need into `src/`
- ✅ Delete `/tmp/libs` when done

---

## CURRENT BUILD (ROOT-ONLY, SINGLE KMP GRADLE)

```bash
./gradlew compileKotlinJvm        # JVM compile
./gradlew compileKotlinLinuxX64   # Linux native
./gradlew compileKotlinWasmJs     # WASM
./gradlew jvmTest                 # JVM tests
```

No subprojects. No composite builds. No `libs/`.

---

## GRAALVM POLYGLOT (PROVEN WORKING)

Dependencies in `build.gradle.kts` → `jvmMain`:

```kotlin
implementation("org.graalvm.polyglot:polyglot:25.0.2")
implementation("org.graalvm.polyglot:js-community:25.0.2")
implementation("org.graalvm.polyglot:python-community:25.0.2")
implementation("org.graalvm.truffle:truffle-api:25.0.2")
```

Usage in `src/jvmMain/kotlin/...`:

```kotlin
val ctx = Context.newBuilder().allowAllAccess(true).build()
ctx.eval("js", "1 + 2")   // 3
ctx.eval("python", "1 + 2") // 3
```

---

## ENFORCEMENT

This directory is tracked in git **only** to prevent accidental re-creation.  
Any PR adding files under `libs/` (other than updates to this `DONTUSE.md`) will be **rejected**.