# Legal counsel panels and trial simulation

## The demonstrable task

Given a pinned agreement, a question graph and an evidence frame, produce a source-linked issue map: relevant clauses, proposed interpretations, triggering conditions, exceptions, competing arguments, authorities, missing evidence and possible outcomes. The result is a simulation under stated assumptions. Its value is the inspectable path through those assumptions and sources.

The first live agreement fixture should cover a bounded section with cross-references and exceptions. Retain the full reasonably complex agreement in the source pane, while declaring which sections the run actually analyzed. Expand coverage incrementally; a source viewer showing the whole document must not imply whole-document reasoning.

## Case frame

The frame records agreement capture/version, governing-law assumption, forum, event dates, party roles, selected factual assertions, disputed assertions, authority collection, question and analysis cutoff. Each assertion has support or is explicitly hypothetical. A user-supplied story is an attributed input, not automatically authenticated evidence.

Distinguish substantive questions from procedural questions. “Was access restricted?” is an event question. “What contractual procedure applies?” is a document interpretation question. “Which tribunal decides?” is procedural. “What outcome is likely?” is a prediction requiring a separate calibrated basis. The UI should not blend them into one score.

## Initial question graph

| Family | Root question | Required branching dimensions |
|---|---|---|
| Access restriction | What follows if access is restricted? | Stated reason, notice, agreement version, disputed event facts |
| Transfer problem | What follows if a transfer is delayed or disputed? | Transaction type, timing, authorization, retained communications |
| Account action | What follows if the user breaches a stated condition? | Exact condition, exception, actual versus assumed conduct |
| Amendment | Which version applies to a later event? | Effective dates, assent evidence, product/jurisdiction |
| Dispute process | What process does the selected agreement describe? | Notice, forum, arbitration terms, other agreements |
| Conflicting instruments | What happens if two retained documents differ? | Subject matter, chronology, incorporation and authority |

Each root has at least four fixtures: support present, support absent, explicit contrary support, and conflicting support. This yields a minimum 24-case matrix before temporal and cross-reference variants. Expected answers are semantic statuses and source links, not a prewritten verdict.

## Panel roles

**Clause analyst:** identifies exact wording, definitions, cross-references and candidate conditions. It emits a structured reading with unresolved scope markers. Deterministic markers and local syntax observations can assist; a model may propose a reading but cannot approve it.

**Claimant counsel:** constructs the strongest supported argument under the selected claimant theory and lists assumptions and adverse evidence. It cannot introduce facts from general model memory as case evidence.

**Respondent counsel:** does the same for the opposing theory. It is a role in the simulation, not an independent source merely because it is a separate agent.

**Authority checker:** checks whether cited authority exists in the retained collection, identifies court/date/procedural posture and links the relevant holding or passage. A missing authority becomes a research task or unresolved citation; an offline run does not fetch silently.

**Adjudicator:** applies the configured comparison procedure to the argument graph. It returns findings relative to the frame, unresolved issues and the minimum additional evidence needed. It can abstain. A deterministic adjudicator can compare rule satisfaction and conflicts without generating an essay.

**Procedure controller:** enforces stage order, resource budgets and expansion rules. It is separate from the adjudicator's substantive assessment. A panel may have several seats per role or omit roles with a documented reduction in coverage.

## Argument representation

An argument record contains a claim, supporting premises, inference scheme, source spans, assumptions, objections and scope. Attack edges distinguish rebuttal of the conclusion, attack on a premise and challenge to the inference. Priorities are explicit: a source's court hierarchy, date or agreement-specific applicability cannot be reduced to an unexplained generic weight.

The first implementation uses a small deterministic conflict procedure: unsupported premises remain pending; contradictory premises mark the affected argument conflicted; source mismatch makes it ineligible; a satisfied rule yields a scoped supported conclusion. More sophisticated argumentation semantics are a separately evaluated extension. Record the chosen semantics in every result.

## Four-way premise status

| Positive support | Negative support | Status | Conditional behaviour |
|---|---|---|---|
| No | No | Pending | Do not fire |
| Yes | No | Supported | May satisfy a positive guard |
| No | Yes | Opposed | May satisfy an explicitly negative guard |
| Yes | Yes | Conflicted | Hold or branch under declared conflict policy |

The existing constellation premise interface already separates explicit positive/negative assumptions. Preserve that distinction. An `unless` condition requires appropriately established negative support under the selected semantics; missing data is not enough. Do not map this four-way display directly into a single NAL confidence number.

## Worked synthetic clause

Use a clearly fictional training clause: “A reviewer may release a draft if two assessments are complete, unless a source is withdrawn.” This is a fixture, not Coinbase language.

Represent `assessmentComplete(A)`, `assessmentComplete(B)` and `sourceWithdrawn(S)` separately. In scenario S1, both assessments are supported and withdrawal is unresolved: release remains pending. In S2, both assessments are supported and explicit non-withdrawal is current: the simulated condition is eligible. In S3, withdrawal is supported: release is blocked. In S4, withdrawal and non-withdrawal both have support: the result is conflicted. An actual release still needs the host's production/effect authorization even in S2.

This example demonstrates the important crossing: document semantics can establish an eligible branch while the action boundary still checks authority and currentness.

## Real authority fixture

The official 2024 opinion in *Coinbase, Inc. v. Suski* is useful because it involves two agreements and the question of who decides which governs. The Court's decision concerned that contract-selection/arbitrability issue; it should not be generalized into a merits outcome for unrelated customer disputes. Retain the opinion itself, distinguish its syllabus from the Court's opinion, and anchor the precise passage used. [Official Supreme Court opinion](https://www.supremecourt.gov/opinions/23pdf/23-3_879d.pdf)

Use it as a test of authority mapping: does the panel recognize a question about conflicting agreements, preserve factual differences, and avoid importing the result when only one agreement is supplied? A current live agreement capture must not be substituted for the historical contracts in that case. Current-law completeness and subsequent treatment require a separately maintained authority collection.

## Recursive walkthrough

At each unresolved issue, the controller can create children for missing facts, alternative readings, conflicting authority or counterfactual actions. Each child inherits the frame and adds a bounded delta. The parent retains all children and their terminal reasons. A selected path does not erase unselected alternatives.

Start breadth-first with the six root families. Expand only issues that could change a specified output. If a missing fact has no effect on the current question, keep it as context rather than spawning work. If a child repeats the same question and assumption set, link to the prior subproblem. If the budget ends, publish the frontier and the effect of incomplete exploration.

## Trial simulation mechanics

Stages are pleadings/issue framing, evidence inventory, objections, argument, response and decision report. These are configurable procedural heuristics, not a universal court model. An evidence admissibility flag is an assessment under a named scenario policy, not actual legal admissibility.

A judge simulation cannot resolve disputed facts by averaging counsel outputs. It may branch on alternative fact findings or identify a required fact-finder decision. Outcome probabilities require separately labeled empirical calibration; panel agreement alone provides no frequency estimate.

## Output packet

Produce a concise issue map and a detailed appendix: source version, scenario assumptions, exact clauses, argument graph, authority passages, unsupported citations, conflicts, result status, unresolved frontier, engine versions and resource/coverage limits. Every sentence presented as a source-based proposition links to its supporting passage. Generated drafting is labeled as drafting and can be revised without modifying the retained evidence.

## Evaluation

Use annotated source spans and expected status transitions for the initial matrix. Measure condition/exception preservation, cross-reference coverage, unsupported-claim rate, source-version mistakes, authority mismatch and reproducibility. Include role reversal and irrelevant-but-similar evidence. The key success is a system that visibly remains unresolved when required support is absent, then changes only the appropriate branch when the missing evidence arrives.
