Viewserver TS→Kotlin helper

This module provides a small helper task to run the TypeScript compiler (tsc) to emit declaration files (.d.ts) from user-supplied JS/TS source. The intent is to use generated declarations as a starting point for producing Kotlin externs or hand-porting small functions.

Usage

- Generate declarations:

  ./gradlew :libs:couch:viewserver:generateDts

- The task runs `npx tsc -p tsconfig.json` inside the libs/couch/viewserver project directory and writes output per tsconfig settings (see tsconfig.json).

Notes & security

- This is a convenience task only. It does not execute or compile user JS into Kotlin; it only runs tsc to emit type declarations.
- Running user code (or evaluating user JS) must remain sandboxed. The existing ViewServer prototype currently uses kotlin.js.eval; prefer replacing eval with compiled wrappers produced from typed declarations and hand-reviewed translations.

Next steps

- Optionally integrate dts2kt (or a small kursive-based translator) to generate Kotlin externs from .d.ts files.
- Add example usercode and a Gradle workflow to chain tsc → dts2kt → assemble when ready.
