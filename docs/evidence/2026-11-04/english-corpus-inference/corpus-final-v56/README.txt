Final actual English corpus verification — v56

Exact source: e6fd0568905e31ab9ca044f9982b6728fc068858.
Frozen production main JAR SHA256:
c95b96cba473abdb412b04baed9f23fd915f25aba50d92a3d74a519648df0bf1
Documents browser bundle SHA256:
bd77ed9ed7509802cc104a3a1dfc19aba380ac6cc60484894b573ad34fe0dfc5
The coordinated build passed123selected JVM checks,0failures/errors/skips,
and built Documents/Pages/Spacegraph. This fresh browser run passed attempt1.

This supersedes the earlier v54 corpus UI verification for final delivery.
The intermediate v55 attempt exposed a real panel-open race after reload: opening
native details and immediately changing source mode repainted it closed before
its queued toggle event updated controller state. That failed attempt and figure
are retained. The fix reads the actual details.open state before repainting and
preserves the explicit reveal override. This final run kept the same immediate
open-then-mode-change actions; no artificial wait was added to conceal the race.
The only harness adjustment improved error reporting for an unawaited response
waiter if a click fails. It changes neither actions nor their timing.

Actual workflow and data
Two synthetic TXT files were natively dragged onto Documents through real
ProjectDbWire/PatchWire routes, creating a Manual project and retaining the old
selection until explicit navigation. The production uploader called the actual
miner; TXT inputs produced zero mining work, extractions or minted beliefs.
Actual local CoreNLP4.6.0 SYNTAX produced source-bound inspection receipts. One
Enhanced++ copy node occurred in the requirements sentence. Corpus statistics
counted94original observed tokens, not95parser nodes:84included lexical tokens,
53distinct terms. The requirement lemma counted once despite its copied node.

Use this inspection in corpus selected exactly two receipts. Reload/navigation
preserved only project-scoped opaque IDs in browser storage. No corpus build or
topic fit happened automatically. The TF cloud matched every original-token
lemma count. Requirement and not clicks returned exact source quotations/spans,
including original uppercase negation and a correctly encoded current-document
link. Snapshot quotations were not substituted with newer source text.

LDA was separately opted into with2topics,30iterations,seed1,alpha0.1,beta0.01.
Repeated input matrix/settings produced identical topic CID within this run:
sha256:b4e71db9e564aac3d2740d29bdc6184ee8991b9a48771340aa15238681d5328b
Topic counts conserved all84included lexical tokens and original term counts.
These unfiltered, function-word-heavy topics are descriptive statistics; this
is no demonstration of legal comprehension, topic quality on general corpora,
validity, independent corroboration or calibrated NARS confidence.

An overlapping partial inspection yielded a visible HTTP409 refusal. Source
replacement yielded HTTP409 in current mode; retained-snapshot mode replayed
the old source data with historical/not-currently-checked status and identical
topic CID. The actual registered document.english.corpus LCNC runner consumed
these same real inspection IDs and returned identical matrix/core IDs/counts.
A held late build did not paint another selected source after navigation.
Seven derived corpus/matrix/topic CAS identities were read back and verified.

No generative model, review or reasoning admission
The fixture had no configured model, HTX provider owner, belief bag or review
owner. Model calls, automatic intake, explicit review intake, Rete facts, browser
page errors and external browser requests all remained zero. Execution identity
includes actual SDK metadata and observed source/hash boundaries. Source bytes
and structure are checked; a receipt alone does not authenticate parser execution
or establish the legal authority of its source.

The fixture inherited a seccomp EACCES filter on connect/sendto/sendmsg/sendmmsg.
Python pre-exec selfprobes and actual before/after Java TCP/UDP probes were refused;
inbound loopback HTTP accept/read/write remained available. This is a stated
fixture-process outbound syscall restriction, not a complete OS sandbox, filesystem
isolation or a general production daemon air-gap claim. The external HTTP transport
forwarded production routes, not the full daemon listener. Registered LCNC-node
execution is not a complete visual-canvas or Camel proof. No production class
overlays, live production class directories or friend access were used.

Browser and fixture exited zero. All owned parser owners drained and the listener
was checked closed. The failed v55 and earlier successful v54 proof directories
remain unchanged. Only synthetic sources and controlled fixture data are public.
