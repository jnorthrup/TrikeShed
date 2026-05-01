# libs/kursive

NARS (Non-Axiomatic Reasoning System) parser combinator library and IKR
(Incomplete Knowledge and Resources) reasoning VM. Implements the Narsive
grammar for NAL (Non-Axiomatic Logic) levels 1-9 using a backtracking
parser combinator framework (Jursive), with operator rendering in ASCII and
Unicode. Includes a channel-based NARS3 Machine for atom-based IKR exploration.

## What It Is (Mechanically)

Three layers:

1. **Jursive** — A generic parser combinator framework operating on
   `Series<Char>` via `JursiveCharSeries` (position-tracking, checkpoint/rewind,
   event tracing). Combinators: `bb` (sequence), `choice` (alternation),
   `then` (sequence parsers), `or` (alternation parsers), `colon` (named
   production). Steps (boolean parsers) compose via `then`, `or`, `opt`,
   `repeat(min)`.

2. **Narsive** — The NARS grammar implemented as Jursive parsers. Parses NAL
   tasks/sentences into typed `NarsiveElement` trees with `NarsiveOperator`
   classification (copulas, conjunctions, tenses, variables). Elements are
   `CoroutineContext.Element` keyed by `NarsiveElementKind` (which doubles as
   `CoroutineContext.Key`). A `NarsiveSupervisorJob` provides fanout by element
   kind and concurrent resolution series.

3. **NARS3 Machine** — A channel-based reasoning VM. `Nars3Atom` is the
   fundamental unit: it processes `Channel<Nars3Message>` input and produces
   output. Atoms chain into arena pipelines with bottom-to-top refeeding.
   Budget (priority/durability/quality) decays at each step; messages below
   threshold are dropped. `Nars3Machine` manages `SupervisorJob`-scoped chains.

## Source Layout

```
src/commonMain/kotlin/borg/trikeshed/parse/kursive/
  Jursive.kt     — Parser combinator framework:
                     SeriesBuffer<T>, JursiveCharSeries (checkpoint/rewind/emit),
                     KursiveParser<T> (Join<Series<Char>, (JursiveCharSeries) -> T?>),
                     KursiveStep (boolean parser), composition operators (then/or/opt/repeat),
                     bb (named sequence), choice, colon (named production),
                     parse/parseLines entry points, TypeEvidence integration,
                     KURSIVE_EVIDENCE_COLUMNS schema

  Narsive.kt     — NARS grammar and element model:
                     NarsiveElementKind enum (21 kinds, CoroutineContext.Key<NarsiveElement>),
                     NarsiveElement (kind + span + lexeme, CoroutineContext.Element),
                     NarsiveSupervisorJob (fanout by key, concurrent resolutions),
                     NarsiveOperator enum (19 operators, BitMaskedLong, ASCII/Unicode render),
                     NarsiveRenderMode, operator lexing helpers,
                     Narsive grammar object (word, numeric, quoted, copula, conjunction,
                       tense, variable, term, budget, truth, relationship, operation,
                       compoundTerm, statement, judgement, goal, question, sentence, task),
                     trace→element/supervisor adapters, operator masking

  NAL.kt         — NAL level taxonomy:
                     NALLevel enum (NAL1-NAL9), each with primary operator + description,
                     fromOperator/fromString lookup

  std.kt         — Standard parser library:
                     ws, lineBreak, ch, lit, takeWhile, takeUntil, quoted, number,
                     separated, trimmed, restOfLine, step shorthands (wss, nums, qs)

  nars3/
    Nars3Machine.kt — IKR reasoning VM:
                        Nars3Budget, Nars3Message, Nars3Atom (abstract),
                        LocalAtom, ChannelizedAtom (concrete),
                        Nars3Machine (SupervisorJob, arena chain construction,
                        bottom-to-top refeeding, shutdown)

src/commonTest/kotlin/borg/trikeshed/parse/kursive/
  NALTest.kt              — NAL 1-9 parsing and supervisor job fanout (28 tests)
  NALParserContractTest.kt— Parser contract tests
  NarsiveParserTest.kt    — Task/sentence/operation parsing, supervisor job fanout,
                              line chunking, Unicode operator rendering (12 tests)
  NarsiveDiag.kt          — Diagnostic/helper code for tests

src/jvmTest/kotlin/borg/trikeshed/parse/kursive/
  KursiveCcekTest.kt      — JVM-specific parser tests

src/commonTest/kotlin/borg/trikeshed/parse/kursive/sql/
  SqlParserTest.kt        — SQL parser tests (proves Jursive is general-purpose)
```

## Key/Element Pattern Status

This module **is** the Key/Element infrastructure for NARS parsing. It does not
use the TrikeShed `AsyncContextKey/AsyncContextElement` pattern — instead it
uses Kotlin's `CoroutineContext.Element` and `CoroutineContext.Key` directly:

| Element            | Key (CoroutineContext.Key)     | Role                               |
|--------------------|--------------------------------|-------------------------------------|
| NarsiveElement     | NarsiveElementKind (21 values) | Parsed grammar node + operator      |
| NarsiveSupervisorJob | Job (kotlinx.coroutines)     | Fanout by element kind, SupervisorJob |

`NarsiveElementKind` implements `CoroutineContext.Key<NarsiveElement>` — each
grammar production kind IS its own routing key. This enables
`NarsiveSupervisorJob.fanout(NarsiveElementKind.COPULA)` to extract all copula
elements from a parsed trace.

`NarsiveOperator` implements `BitMaskedLong` — operators are bit-masked for
efficient set operations via `operatorMask()` and `narsiveOperators()`.

No `AsyncContextElement` usage. No `ElementState` lifecycle. This is a pure
parsing/classification module.

## Dependencies

- **TrikeShed core**: `lib` (Series, Join, Twin, CharSeries, j infix, alpha,
  toSeries, plus, size, get, zip), `collections` (s_ builder),
  `common.TypeEvidence`, `cursor` (ColumnMeta, RowVec, TypeMemento,
  MapTypeMemento, SeqTypeMemento, joins, label),
  `isam.meta` (IOMemento), `context.BitMaskedLong`,
  `userspace.concurrency.Job`
- **kotlinx.coroutines** (SupervisorJob, CancellationException, CoroutineScope,
  channels.Channel, launch)
- **kotlin.coroutines** (AbstractCoroutineContextElement, CoroutineContext)
- Build: `../../gradle/macros/trikeshed-lib.gradle`

No external dependencies beyond Kotlin stdlib and kotlinx.coroutines.
