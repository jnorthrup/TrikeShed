# Libpijul KMP Port + Git Gateway Design

## Goal
Port libpijul (Rust) to Kotlin Multiplatform for CRDT-based patch theory, with a bidirectional Git gateway for interop.

## Architecture

```
┌─────────────────────────────────────────────────────────────────────┐
│                      KMP Libpijul Core                              │
├─────────────────────────────────────────────────────────────────────┤
│  commonMain                                                         │
│  ├── pijul/                                                         │
│  │   ├── Patch.kt           — Patch (hunk + dependencies)           │
│  │   ├── Change.kt          — AddFile, AddDir, Remove, Move, Edit   │
│  │   ├── Hash.kt            — BLAKE3 hash (CID-compatible)          │
│  │   ├── RepoState.kt       — Branch state, pristine, patches       │
│  │   ├── Pristine.kt        — In-memory filesystem (BTree + files)  │
│  │   ├── Graph.kt           — Patch dependency graph (CRDT)         │
│  │   ├── ApplyResult.kt     — Success / Conflict / Error            │
│  │   └── RecordOptions.kt   — Author, message, timestamp           │
│  │                                                              │
│  │   ├── backend/                                                      │
│  │   │   ├── Backend.kt          — Storage abstraction              │
│  │   │   ├── CasBackend.kt       — CAS-backed storage               │
│  │   │   └── FileBackend.kt      — Local file storage               │
│  │   │                                                              │
│  │   └── gateway/                                                      │
│  │       ├── GitGateway.kt       — Git ⇄ Pijul conversion           │
│  │       ├── GitImporter.kt      — Git commits → Pijul patches      │
│  │       ├── GitExporter.kt      — Pijul patches → Git commits      │
│  │       └── ConflictResolver.kt — Merge conflicts via CRDT         │
│  │                                                              │
│  ├── jvmMain/                                                         │
│  │   └── jni/                — JNI bindings to libpijul (optional)   │
│  │                                                              │
│  └── nativeMain/                                                      │
      └── cinterop/            — Native libpijul FFI (optional)         │
```

## CRDT Patch Theory

### Patch Identity
- Each patch has a **globally unique hash** (BLAKE3 of: dependencies + change + metadata)
- Patches form a **DAG** via explicit dependencies
- No central authority — patches can be created independently

### Change Types
```
Change =
  | AddFile(path, content_hash, executable)
  | AddDir(path)
  | Remove(path)
  | Move(old_path, new_path)
  | Edit(path, diff_hunk)           // line-level diff
  | Metadata(path, key, value)
```

### Dependency Graph (CRDT)
- **Concurrent patches** = no dependency edge between them
- **Sequential patches** = explicit dependency edge
- **Merge** = union of patch sets + transitive closure of deps
- **Conflict** = two patches editing same lines with different content

## Git Gateway Design

### Git → Pijul (Import)
```
Git commit → Tree diff → Changes → Pijul patches
  │
  ├─ Parse commit: tree, parent(s), author, message, timestamp
  ├─ Diff against parent: tree walk → change list
  ├─ Convert each change to Pijul Change
  ├─ Assign dependencies: parent commits = patch deps
  ├─ Hash = BLAKE3(parents + changes + metadata)
  └─ Apply to pristine
```

### Pijul → Git (Export)
```
Pijul patches (topological order) → Git commits
  │
  ├─ Sort patches by dependency DAG
  ├─ For each patch:
  │   ├─ Apply to Git index
  │   ├─ Create commit with author/message/timestamp from patch
  │   └─ Parent = previous commit in topological order
  └─ Result: linear Git history matching Pijul's causal order
```

### Bidirectional Sync
```
Pijul repo          Git repo
    │                  │
    │  record          │  commit
    │  patch(hash)     │  commit(hash)
    │  with deps       │  with parent
    ▼                  ▼
┌─────────────────────────────┐
│   Mapping Table             │
│  patch_hash ↔ commit_hash   │
│  branch_name ↔ branch_name  │
└─────────────────────────────┘
```

## KMP Implementation Strategy

### 1. Pure Kotlin First (commonMain)
- Implement core CRDT logic in pure Kotlin
- BLAKE3 hashing via `kotlinx-serialization` + `com.soywiz.klock.b3`
- BTree for pristine using `kotlinx.collections.immutable`

### 2. JNI/Native Acceleration (Optional)
- JVM: JNI wrapper around libpijul (Rust `cdylib`)
- Native: Direct cinterop with libpijul
- JS/Wasm: Pure Kotlin fallback

### 3. CasStore Integration
- Patches stored as CAS objects (BLAKE3 = CID)
- Pristine files stored as CAS blobs
- Dependency graph as Confix docs

## API Surface

```kotlin
// Core
interface PijulRepo {
    suspend fun init(branch: String = "main"): Result<Unit>
    suspend fun record(changes: List<Change>, opts: RecordOptions): Result<Patch>
    suspend fun apply(patch: Patch): ApplyResult
    suspend fun unrecord(patchHash: Hash): Result<Unit>
    suspend fun log(branch: String): Series<Patch>
    suspend fun diff(from: Hash, to: Hash): Series<Change>
    suspend fun branches(): Series<String>
    suspend fun checkout(branch: String): Result<Unit>
}

// Git Gateway
interface GitGateway {
    suspend fun importGitRepo(gitDir: File, pijulDir: File): Result<ImportReport>
    suspend fun exportToGit(pijulDir: File, gitDir: File): Result<ExportReport>
    suspend fun sync(pijulDir: File, gitDir: File): Result<SyncReport>
}

// CRDT Merge
interface PatchSet {
    fun union(other: PatchSet): PatchSet
    fun intersect(other: PatchSet): PatchSet
    fun conflicts(): Series<Conflict>
}
```

## Verification

```kotlin
// Round-trip test
suspend fun testGitPijulRoundtrip() {
    val gitDir = createTempGitRepo()
    val pijulDir = createTempPijulRepo()
    
    // 1. Git → Pijul
    GitGateway.importGitRepo(gitDir, pijulDir)
    
    // 2. Pijul → Git (new repo)
    val gitDir2 = createTempGitRepo()
    GitGateway.exportToGit(pijulDir, gitDir2)
    
    // 3. Verify commit graph isomorphism
    assertCommitGraphsEqual(gitDir, gitDir2)
    
    // 4. Verify content equality
    assertTreesEqual(gitDir, gitDir2)
}
```

## Dependencies

| Crate/Lib | Purpose | KMP Target |
|-----------|---------|------------|
| `blake3` | Hashing | JVM/Native/JS (pure Kotlin) |
| `b-tree` | Pristine index | `kotlinx.collections.immutable` |
| `libpijul` | Reference impl | JNI (JVM), cinterop (Native) |
| `git2` | Git ops | `kt-git` / JGit |

## Migration Path

1. **Phase 1**: Pure Kotlin CRDT core + CAS backend
2. **Phase 2**: Git gateway (import/export)
3. **Phase 3**: JNI binding for performance
4. **Phase 4**: Native cinterop for native targets
5. **Phase 5**: Wasm/JS for browser PWA

## Files to Create

```
src/commonMain/kotlin/borg/trikeshed/pijul/
├── Hash.kt
├── Change.kt
├── Patch.kt
├── RepoState.kt
├── Pristine.kt
├── Graph.kt
├── ApplyResult.kt
├── RecordOptions.kt
├── backend/
│   ├── Backend.kt
│   ├── CasBackend.kt
│   └── FileBackend.kt
└── gateway/
    ├── GitGateway.kt
    ├── GitImporter.kt
    ├── GitExporter.kt
    └── ConflictResolver.kt
```