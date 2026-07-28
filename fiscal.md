# TrikeShed Fiscal Organization — arrangement v1 (2026-07-28)

Status: ARRANGED, nothing spent, no accounts created except what is marked live.
This file is the fiduciary ledger of record. No EINs, bank numbers, or phone
numbers in this file — entities are referenced by role; numbers stay in the
user's records.

## 0. Identity anchors

| Anchor | Value | State |
|--------|-------|-------|
| Public email | forgetrikeshed@gmail.com (canonical registered form; Gmail ignores dots — forge.trikeshed@gmail.com is the SAME inbox) | created (user) |
| Agent inbox access | — | NOT wired. Options: (a) user adds a Google app password so the agent's email tooling (gws/himalaya) can read verification codes; (b) user relays codes in chat. Chosen by acting. |
| Phone | — | none on record for agent. Rails needing SMS: user relays, or register Google Voice under the gmail (free; becomes agent-readable once (a) is done). |
| GitHub org | `trikeshed` | name AVAILABLE (checked 2026-07-28, also `trikeshed-org`). Create under the gmail; becomes the Sponsors/FUNDING target. Repo stays at jnorthrup/TrikeShed for now (transfer preserves stars; defer). |
| Domains | trikeshed.org / .com | REGISTERED — confirm ownership; if user's, point when needed |
| Domains open | trikeshed.dev (~$12/yr), trikeshed.fund (~$30/yr) | AVAILABLE — purchase deferred; no rail below needs one |

## 1. Entity fleet (roles)

| Entity | Tax form | Role | Rails attached |
|--------|----------|------|----------------|
| S-corp | 1120-S | OPERATING — receives work income, pays expenses | Polar/Stripe payout, GH Sponsors bank, grant contracts |
| Estate-corp (99-xxxxx) | CP 575 decides: 1041 fiduciary or 1120 | HOLDING — equity/IP only, NEVER operating income | C-corp shares (or S-corp shares ONLY if 1041) |
| Defunct C-corp | 1120 (dormant) | INVESTMENT vehicle — revive only on a real investor | future equity; new shares QSBS-eligible |
| Sole prop / sole corp | Sch C / varies | RESERVE — no rails | none |
| Trust holding co | unformed | ESTATE layer — form with attorney when C-corp revives | holds C-corp stock |

Rules that govern every rail below:
1. Income never passes through the estate layer (fiduciary brackets compress
   to 37% at ~$15-16K). Equity/appreciation parks there; income does not.
2. A trust or corporation never holds S-corp stock (kills the S election
   retroactively) unless a QSST/ESBT election is filed by the attorney.
3. Sponsorships and grants are TAXABLE revenue to for-profit recipients.
   Donations via Open Source Collective are not deductible to the giver
   (OSC is 501(c)(6)); the deductible-donation route is NumFOCUS or
   Software Freedom Conservancy (501(c)(3), application-based) — deferred
   until a giver actually asks for it.

## 2. Money rails

| Rail | Fee | Intake entity | Status | Next action (owner) |
|------|-----|---------------|--------|---------------------|
| GitHub Sponsors | 0% from personal-account sponsors; ≤6% from org-account sponsors | S-corp bank | FUNDING.yml live (`github: [jnorthrup]`) | join at github.com/sponsors/accounts (user, ~15 min: bank + W-9/EIN + 2FA) |
| Open Source Collective | 10% of incoming | none — OSC is the fiscal host | not applied | opencollective.com/opensource/apply (user, or agent once inbox wired) |
| Polar | 5% + 50¢/txn free plan; MoR eats sales tax/VAT; USD only | S-corp via Stripe | not created | polar.sh org + Stripe connect (user: EIN, CP 575, bank) |
| Liberapay | 0% platform (processor fees only) | S-corp via Stripe/PayPal | not created | liberapay.com signup (2 min) |
| NLnet NGI Commons Fund | grant | S-corp or individual | next deadline ~2026-10-01 (Aug 1 call too tight, wrong theme) | proposal draft (agent, on request) |
| Sovereign Tech Fund / Fellowship | grant | entity or individual | open application | application draft (agent, on request) |
| Tidelift | — | — | DEPRIORITIZED: site redirects into Sonar; lifter terms unverifiable | none |

## 3. Money flow

```
donors ──────────> Open Source Collective ──> expense reimbursements (OSC-approved)
work income ─────> GH Sponsors / Polar / grants ──> S-corp checking ──> payroll + expenses
investor (future) > revived C-corp ──> shares held by trust / estate-corp (per CP 575)
estate layer <──── equity + IP only (no operating income)
```

## 4. Expense policy + ledger

Pre-approved categories (small monies): domain on decision (~$12-40/yr),
C-corp revival/state fees (on investor trigger only), rail fees (OSC 10%,
Polar, Stripe — charged on intake, never out-of-pocket). Anything else needs
a line here first.

| Date | Item | Amount | Paid from | Entity/rail |
|------|------|--------|-----------|-------------|
| —    | (none yet) | — | — | — |

## 5. Sequence of operations

1. [ ] user: join GH Sponsors (lights the repo sponsor button)
2. [ ] user: OSC application — approval takes days, start now
3. [ ] user: create GitHub org `trikeshed` under the gmail (free) → then agent flips FUNDING.yml to `github: [trikeshed]`
4. [ ] user: Polar org + Stripe connect (S-corp EIN, CP 575, bank)
5. [ ] user (or agent once inbox wired): Liberapay
6. [ ] user decision: gmail app password for agent — then agent can self-serve 2, 5 and all future verifications
7. [ ] user→CPA: confirm whether 99-xxxxx files 1041 or 1120 — gates holding-layer placement (§1 rule 2)
8. [ ] agent (on request): NLnet Commons proposal draft for the Oct 1 deadline
9. [ ] deferred: domain purchase, C-corp revival, trust formation — all gated on real money events
