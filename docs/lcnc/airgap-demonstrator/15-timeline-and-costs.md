# Round the clock implementation timeline and costs

## Planning result

The target is an instrumented vertical slice in 4–7 calendar days, an integrated demonstrator in 18–32 days, and an offline release candidate in 45–75 days. Broader inference, larger corpora and empirical production learning continue over 3–6 months. These estimates assume the existing repository foundations remain usable, six agent lanes can operate most days, and the initial scope stays within the bounded contracts in this package.

The first 48 hours must replace assumptions with measured cost and throughput. The user requested a plan for Sol/Terra heavy lifting at high reasoning effort; this document budgets that campaign. It does not start it or authorize unlimited spending.

## Researched model tariffs

Prices were checked on 4 November 2026. The official page presents Standard, Flex/Batch, Fast and Ultrafast separately. Use the applicable account/model/context tariff at execution time. The selected short-context Standard and Fast rows are below, in USD per million tokens. [OpenAI API pricing](https://developers.openai.com/api/docs/pricing)

| Model | Standard input | Standard cached input | Standard output | Fast input | Fast cached input | Fast output |
|---|---|---|---|---|---|---|
| GPT-6.1 Sol | 2.00 | 0.10 | 10.00 | 4.00 | 0.20 | 20.00 |
| GPT-6.1 Terra | 1.00 | 0.05 | 5.00 | 2.00 | 0.10 | 10.00 |

These rates are a planning snapshot, not a quote for Codex subscription credits or every endpoint. Long context, cache-write policies, regional endpoints, tools and other services can change billing. Reasoning effort such as ultra is not the same as Ultrafast processing. There is no justified fixed “ultra multiplier” here; higher effort affects actual billed token use and latency, which must be measured.

## Cost formula

For each model and tariff:

`model cost = uncached_input_M × input_rate + cached_input_M × cached_rate + billed_output_M × output_rate`

Use billed output including any billed reasoning tokens reported by the provider. Do not count cached tokens both as uncached and cached. Add separately billed cache writes, tools, compute and storage when applicable. Track actual provider usage/invoice categories; visible response length is not the bill.

For work planning:

`active hours/day = lanes × 24 × active utilization`

`calendar lower bound = estimated active work hours / active hours/day`

`delivery date = max(work-capacity bound, dependency critical path) + required soak/review windows`

The capacity bound is not a promise that six agents can divide every task evenly.

## Token-use scenarios

All volumes below are assumptions per active agent-hour, in millions of tokens. They include repeated context use and coding/review iterations; they are not measured in this planning task. A 50/50 Sol/Terra mix is used for comparison.

| Profile | Uncached input | Cached input | Billed output | Mixed Standard $/hour | Mixed Fast $/hour |
|---|---|---|---|---|---|
| Lean | 0.15 | 0.60 | 0.06 | 0.72 | 1.44 |
| Base | 0.50 | 2.00 | 0.20 | 2.40 | 4.80 |
| Heavy | 1.00 | 4.00 | 0.75 | 7.425 | 14.85 |

Base Fast arithmetic: Sol costs 0.5×4 + 2×0.2 + 0.2×20 = $6.40/hour; Terra costs $3.20/hour; the equal mix is $4.80/hour. Heavy Fast is $19.80 Sol and $9.90 Terra, averaging $14.85. These are workload examples, not model throughput claims.

Cache failure matters. With base volumes and no cache savings, treating the 2M cached-input assumption as uncached adds $5.70/hour to the equal Fast mix. The mixed rate becomes $10.50/hour. Measure actual cache hit categories before relying on the base estimate.

## Campaign capacity

| Lanes | Utilization assumption | Active hours/day | Base Fast model/day | Heavy Fast model/day |
|---|---|---|---|---|
| 2 | 65% | 31.2 | $149.76 | $463.32 |
| 4 | 65% | 62.4 | $299.52 | $926.64 |
| 6 | 65% | 93.6 | $449.28 | $1,389.96 |

One lane owns contracts/integration. One provides independent review and evidence. Four implement bounded slices. These roles can rotate. More lanes can increase merge collisions, repeated context costs and review queues; add capacity only when ready work and integration throughput justify it.

## Work-based budget

The 25 packages sum to **1,056–2,028 active hours**. Apply a **50% integration/rework effort reserve** for 1,584–3,042 hours. This covers cross-package repair and additional evidence runs, not expanded research scope. At six lanes and 65% utilization, the work-capacity bound is approximately 17–33 days.

At the base Fast mixed rate, that effort costs **$7,603–$14,602** in model usage; with a separate 30% price/token-estimation contingency it is **$9,884–$18,982**. At the heavy rate it costs **$23,522–$45,174**, or **$30,579–$58,726** with the same contingency. The 30% reserve covers uncertainty in per-hour cost; it does not add more implementation hours on top of the already reserved effort.

These work-based totals can be lower than the round-the-clock ceiling because agents waiting for integration, soak tests or a domain decision need not keep generating tokens. Avoid idle conversations that repeatedly re-read unchanged context.

## Calendar spending ceilings

The following assumes all six lanes maintain 65% active time for every calendar day in the period and adds 30% model-cost contingency. It is a capacity ceiling for planning, not the same estimate as package-based effort.

| Milestone window | Base Fast reserved model budget | Heavy Fast reserved model budget |
|---|---|---|
| 7 days | $4,088 | $12,649 |
| 18–32 days | $10,513–$18,690 | $32,525–$57,822 |
| 45–75 days | $26,283–$43,805 | $81,313–$135,521 |
| 90–180 days | $52,566–$105,132 | $162,625–$325,251 |

The 90–180-day row includes a continuously staffed research programme beyond the initial packages. It should not be mistaken for the minimum price of the demonstrator. Scale lanes down when only one research dependency remains.

## Non-model costs

Machine costs depend on the chosen provider and hardware. For planning only, four always-on development/benchmark hosts at an assumed $1–$4 per host-hour cost $96–$384/day. This is an illustrative rate range, not a researched cloud quote. Over 45–75 days the envelope is approximately $4,320–$28,800. A purchased workstation has a different amortization model.

Budget domain review separately. An illustrative 40–120 hours at $150–$350/hour gives $6,000–$42,000 for expert annotation/review; both quantity and rate are assumptions. An entirely unattended technical demo can use reviewed synthetic fixtures, but general legal accuracy cannot be established by agents agreeing with one another.

Storage, backup, licensing, corpus rights and local hardware are separate line items. Event-volume arithmetic in the CPU chapter explains why retention policy matters. No external data, model or commercial license purchases are included in the model totals.

## Milestone schedule

| Window from start | Deliverable | Critical work | Exit condition |
|---|---|---|---|
| Hours 0–48 | Baseline, schemas, one retained trace | WP01–02, thin WP03/WP12 | Actual production route and measured agent cost |
| Days 3–7 | D1 vertical slice | WP03–05, WP11–13 subset | Source → CPU operation → result, live events and pause |
| Days 8–14 | Multi-source analysis and control | WP06–10, WP14–16 | Exact frames, branch accounting, native proof and fork |
| Days 15–24 | Production loop and alternate pack | WP17, WP19–21 | Shadow reuse, generation fencing and visible invalidation |
| Days 18–32 | D2 integrated demonstrator | WP23/WP25 demo portions | Full storyboard under blocked egress |
| Days 25–45 | Learning and performance evidence | WP18, WP22, WP24 | Measured optional models and CPU capacity envelope |
| Days 45–75 | D3 offline release candidate | WP20, WP23–25 hardening | Clean offline install, recovery and sustained run |
| Months 3–6 | D4 research extensions | Bounded experiments from decision register | Broader capability only after evidence gates |

Intervals overlap because independent adapters and UI work can proceed in parallel after contracts stabilize. The critical chain is source identity → frame/review → execution receipts → proof → production guard/dependency → effect fencing → integrated offline recovery. Cosmetic renderer work is not allowed to conceal a missing link in that chain.

## First forty eight hour recalibration

Record active time, input/cached/output tokens, cost, completed acceptance items, compile/integration wait, rework and failure categories per package. Compute cost per accepted work unit and actual critical-path progress. If fewer than two coherent vertical slices land, reduce concurrency and fix contracts before expanding scope.

If model costs exceed the heavy envelope, inspect context repetition, cache misses, oversized tasks and repeated failed approaches. Preserve deep reasoning for unresolved semantics; use smaller bounded handoffs for mechanical work. If the first parser/solver isolation task reveals structural runtime debt, revise the 18–32-day target explicitly rather than compensating with fabricated UI progress.

## Lights out operating policy

Unattended development is feasible for pre-authorized repository changes, bounded experiments and local fixtures with explicit budgets. The coordinator stops spending when there is no ready work, a hard budget limit is reached, a required decision is missing or repeated attempts fail without new evidence. It records a concrete blocker and switches to independent ready work where possible.

Set per-task and daily dollar/token limits, a retry cap, branch ownership, evidence requirements and a known-good integration baseline. Use an outbox of reviewable artifacts instead of sending messages or publishing externally. A human review window can be scheduled around domain and release decisions; continuous machine availability does not eliminate those dependencies.
