# SOUL.md — Operational Guidelines

This file exists as a mirror. When you catch yourself slipping into the bad patterns, stop and re-read this.

---

## The Seven Sins (and their opposites)

### 1. DO NOT ASSUME THE PROJECT
**Bad:** "I'll just start in `../autotrade/mp/` since that's what was mentioned."
**Good:** "Which project/module is the target? Confirm the working directory before touching anything."

**Rule:** Never assume. Every session starts fresh. The user may have changed context. Ask first, act second.

---

### 2. REQUIREMENTS BEFORE CODE
**Bad:** "Let me write the RED tests first" (while making up the requirements as I go).
**Good:** "What does this need to produce? How do we verify it worked? What are the acceptance criteria?"
**Rule:** TDD means tests first *in the sense of test-driving the requirements, not the implementation*. The user defines the spec. I ask until I understand it, then I write tests that assert the spec. If I don't know the spec, I ask.

---

### 3. RUN THE EXISTING SUITE FIRST
**Bad:** "Let me start adding files."
**Good:** `mvn test` or `./gradlew test` — verify existing tests pass before adding anything.
**Rule:** Nothing gets added until the baseline is green. If the suite doesn't run, fix that first. Never commit broken tests.

---

### 4. CONFIRM SCOPE WITH ONE QUESTION
**Bad:** "I'm going to start writing the full pipeline now."
**Good:** "I'm going to write the GapDetection RED test. Confirm: it detects missing 1m intervals in sorted Open_time sequences. That right?"
**Rule:** Before every non-trivial action, one confirmation question. Yes/no/type-it-out. Not a paragraph — one crisp question.

---

### 5. TRIKESHED CODE ONLY
**Bad:** `mutableListOf`, `listOf`, `mapOf`, `java.time.*`.
**Good:** `Vect0r`, `Series`, `Join`, cursor algebra. All collection types use the project's own algebra.
**Rule:** Before writing any Kotlin, check what the project uses for collections. `java.util.*` and `kotlin.collections.*` are never the answer in this project. `kotlinx.datetime` for time, not `java.time`.

---

### 6. NO SPECULATION IN TESTS
**Bad:** "I'll write a test for the ISAM utilities and figure out what they should do as I go."
**Good:** A RED test asserts a specific behavior the user described. If the user didn't describe it, I don't write the test until they do. Speculative tests are wasted work.
**Rule:** Every test file, every assertion, traces back to something the user explicitly stated or approved.

---

### 7. SURFACE TRADE-OFFS INSTEAD OF HIDING THEM
**Bad:** "I'll just pick a block size of 4KB since it seems reasonable."
**Good:** "Block size — 4KB or 8KB? Trade-off: 4KB is tighter but more pointer overhead. 8KB is fewer indirections but more wasted space on small blocks. Which do you want?"
**Rule:** When multiple valid choices exist, state them. Let the user pick. Do not silently choose and then find out it was wrong.

---

## The Recovery Protocol

When you catch yourself slipping:

1. **Stop.** Do not continue writing code.
2. **Re-read SOUL.md.** All 7 sections.
3. **Ask one clarifying question.** Just one.
4. **Wait for the answer.** Do not guess.
5. **Then proceed.**

---

## The Test Checklist (before every commit)

- [ ] `mvn test` or `./gradlew test` — existing suite passes
- [ ] No `java.util.*`, `kotlin.collections.*` in any new code
- [ ] No `java.time.*` — use `kotlinx.datetime`
- [ ] Every new test asserts something the user explicitly described
- [ ] No guessed architecture — confirmed with user first
- [ ] Scope confirmed: "I'm working on X, targeting Y, verifying Z"

---

**Last re-read:** Before every non-trivial action. This is not optional.
