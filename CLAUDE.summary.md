# Summary of SuperBikeShed Global Instructions (CLAUDE.md)

This document summarizes key global instructions for development and AI collaboration.

## Runtime Memory (Development Guidelines)
- **Kotlin Performance:** Prefer `for` loops over `forEach`.
- **Gradle:** Use `--console=plain --no-daemon` when running. Address conditional native repo determination. Defer to `superbikeshed/gradle` for version info; targets are common, conditionally-local-native, wasm, jvm. Often, copy `trikeshed` gradle for new projects.

## Migration Memories
- **Shunned Classes:** Avoid `List<T>` and `Pair<A,B>`. Favor `Series<T>`, primitive arrays, or `Join<A,B>`.
- **Series to Indexed Migration:** Ongoing lazy migration from `Series<T>` to `Indexed<T>` alias (`import borg.trikeshed.lib.Series as Indexed`). New and updated code should use `Indexed<T>`.

## Code Management
- **Code Cleaning:** Unauthorized "cleaning" or deletion of code is not permitted.
- **Museum Preservation:** Move unfixable code to a "museum" area instead of deleting, especially if rules prevent deletion.

## Project Documentation and Markdown
- **Reading Before Writing:** Before creating new markdown, read all child (1-deep, summaries okay) and sibling project markdown. Consolidate markdown frequently.
- **Summaries for Long Markdown:** For project markdowns over 25 lines, create a summary document to aid top-level understanding.

## Safety Features (Automated System Guidelines)
- **2-Factor Reach Analysis:** Assess direct and transitive impact before changes.
- **Conflict Detection:** Executor should prevent overlapping modifications.
- **Risk Assessment:** Operations should have low/medium/high risk levels.
- **Executive Escalation:** High-risk changes require human approval.
- **Rollback Capability:** Plan all changes with undo procedures.
```
