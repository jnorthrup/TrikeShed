1. **Create `JulesDrainDedupeCli.kt`**
   - Create `src/jvmMain/kotlin/borg/trikeshed/jules/JulesDrainDedupeCli.kt`.
   - The CLI should take a repo directory and an optional forge directory.
   - It needs to load all sessions from the WAL (`JulesBoardStore`).
   - It needs to query git to find branches named `jules-*` or `flywheel/jules-*` and extract the session IDs.
   - It needs to emit the deduplicated list of session IDs and their provenances (e.g., WAL, Branch, Tag) as JSON.
2. **Implement git execution helper**
   - Implement `git(repoDir, ...)` using `ProcessBuilder` (wrapped in `withContext(Dispatchers.IO)` and using an allowlist of commands like `ls-remote`, `branch`, `tag`, etc. if applicable, though `ProcessBuilder` is fine for internal CLI as long as we validate). Wait, the memories say: "validate the executable against an explicit allowlist of permitted commands (e.g., git, cp, echo, zstd)".
3. **Check merged PR heads**
   - We might need to check commits that have been merged. Or just query `git branch -a --contains`? Or query merged refs. The prompt mentions "merged PR heads". I will look for `refs/pull/*/head` or similar, or just parse `git ls-remote origin`.
4. **Compile and Verify**
   - Run `./gradlew :jvmMainClasses --no-daemon` to ensure the new file compiles successfully without errors.
5. **Complete pre-commit steps to ensure proper testing, verification, review, and reflection are done.**
6. **Submit**
