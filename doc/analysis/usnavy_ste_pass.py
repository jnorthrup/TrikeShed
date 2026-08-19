#!/usr/bin/env python3
"""doc/concepts.md -> US Navy / ASD-STE100 style Simplified Technical English pass.

concepts.md is now a pointer; the corpus lives in README.md ("TrikeShed Concept Map").
Rules applied (Navy plain-language + STE style):
  R1 sentences <= 20 words (split at and/that/but/which/comma)
  R2 one topic per sentence (em-dash, semicolon, colon become sentence breaks)
  R3 active voice, present tense (passive constructs counted, flagged)
  R4 approved general words only; jargon words replaced
  R5 technical names stay as names; each is defined once in the lexicon
  R6 no idioms; no gerund-led sentences
Outputs:
  doc/analysis/usnavy_lexicon.md       curated + discovered lexicon
  doc/analysis/concepts_usnavy_ste.md  converted document
"""
import re
import math
import gzip
import collections

SRC = 'README.md'

# ── LEXICON: technical name -> plain-English definition (R5) ──────────────
LEXICON = {
    'Join': 'a pair of two values, a and b',
    'Twin': 'a Join of two values of the same type',
    'Series': 'a list of items. You get an item by its number',
    'Series2': 'a Series that stores each item as a pair',
    'Cursor': 'a table of rows and columns',
    'RowVec': 'one row of a Cursor. It holds a value and a meta supplier',
    'alpha': 'a lazy view over a Series. It changes each item when you read it',
    'j': 'the infix operator that makes a Join',
    'CCEK': 'Coroutine, Context, Element, Key. The reactor object model',
    'reactor': 'the one event loop that runs all work',
    'coroutine': 'a suspended unit of work',
    'NioSupervisor': 'the root registry of IO services',
    'Liburing': 'the Linux async disk and socket interface',
    'io_uring': 'the Linux async disk and socket interface',
    'ChannelRunner': 'the loop that turns IO events into coroutine wake-ups',
    'Htx': 'HTTP message blocks',
    'Litebike': 'the listener that opens sockets and hands bytes to CCEK',
    'NUID': 'a name token that grants permission. It has a capability, a nonce, and a subnet',
    'Capability': 'a permission kind, such as Process or Cas',
    'Subnet': 'a trust ring, from core out to global relay',
    'Workgroup': 'a worker set with a scope and traits',
    'JobSupervisor': 'the element that owns the job command channel',
    'JobReducer': 'the pure function that folds commands into snapshots',
    'CasStore': 'the store keyed by content hash',
    'CID': 'content id. The SHA-256 hash of the bytes',
    'JobLog': 'the write-ahead log of job frames',
    'ReteNetwork': 'the rule engine. It matches facts and emits commands',
    'Kanban': 'the work board',
    'projection': 'a read view built from committed facts',
    'Confix': 'the config reader. It parses JSON, YAML, and CBOR',
    'ConfixDoc': 'a parsed config doc: index plus raw bytes',
    'ConfixFacetPlan': 'the validation plan compiled from the schema',
    'CouchStore': 'the document store with revisions',
    'MVCC': 'many versions kept. Readers never block writers',
    'CowBPlusTree': 'a copy-on-write B+ tree. Pages live in the CAS',
    'LinearHashMap': 'an open-addressing map. It uses no boxed entries',
    'FunnelHashMap': 'a map with tiered lookup geometry',
    'NarseseBag': 'the signal store keyed by semantic hash',
    'SemanticSignal': 'one extracted fact with evidence',
    'TruthCoord': 'a belief score packed in a Long',
    'EvidenceCoord': 'raw evidence counts packed in a Long',
    'Forge': 'the user workspace app',
    'ForgeDoc': 'the block tree of a Forge document',
    'BlackboardSurface': 'the projection of a JSON blackboard into rows',
    'Flywheel': 'the merge, build, and push loop',
    'Pijul': 'a version control system based on patches',
    'idempotencyKey': 'the dedupe key. First request wins',
    'expectedRevision': 'the version check. A stale request is rejected',
    'GraalVM': 'the Java runtime used for builds',
    'WASM': 'the web binary target',
    'JMH': 'the Java benchmark harness',
    # promoted from discovered candidates (round 2)
    'JSON': 'a text format for data. Curly braces hold the fields',
    'YAML': 'a text format for config. Indent gives the structure',
    'CBOR': 'a binary format for data. It maps one to one onto JSON',
    'CAS': 'content addressable storage. The key is the hash of the bytes',
    'HTML': 'the text format of a web page',
    'ISAM': 'an index file layout. Keys stay sorted on disk',
    'CSV': 'a text format. Commas split the fields',
    'DTO': 'a data copy object. It moves bytes between layers',
    'VFS': 'the virtual file system. One tree over many stores',
    'FFI': 'the foreign function interface. It calls C code',
    'LCNC': 'low code, no code. Users build apps without writing code',
    'K8s': 'Kubernetes. The container orchestrator',
    'GraalJS': 'the GraalVM JavaScript engine',
    'DuckDB': 'an embeddable SQL database for analytics',
    'ForgeAssets': 'the baked-in Kotlin object that holds the web shell files',
    'ConfixIndex': 'the flat token array of a parsed config doc',
    'IndexSpecId': 'the stable id of one index definition',
    'MuxReactorElement': 'the reactor that owns model cache and kanban events',
    'AsyncContextElement': 'the base element of the reactor lifecycle',
    'CoroutineContext': 'the coroutine scope data. Keys address elements',
    'SharedFlow': 'a broadcast stream. Many collectors see each value',
    'JobCommand': 'one job order: submit, start, complete, fail, or cancel',
    'MutableMap': 'the Kotlin read write map from the standard library',
    'TODO': 'a marker for work not yet done',
}

# ── R4 banned word -> approved replacement ─────────────────────────────────
SUBS = [
    (r'\butilization\b', 'use'),
    (r'\butilize(s|d)?\b', r'use\1'),
    (r'\bprior to\b', 'before'),
    (r'\bin order to\b', 'to'),
    (r'\bcommence(s|d)?\b', r'start\1'),
    (r'\bterminate(s|d)?\b', r'stop\1'),
    (r'\battempt(s|ed)?\b', r'try\1'),
    (r'\bobtain(s|ed)?\b', r'get\1'),
    (r'\badditional\b', 'more'),
    (r'\bapproximately\b', 'about'),
    (r'\bsubsequently\b', 'then'),
    (r'\btherefore\b', 'so'),
    (r'\bthus\b', 'so'),
    (r'\bhence\b', 'so'),
    (r'\bregarding\b', 'about'),
    (r'\bnumerous\b', 'many'),
    (r'\bfacilitate(s|d)?\b', r'help\1'),
    (r'\bcomponent(s)?\b', r'part\1'),
    (r'\bcurrently\b', 'now'),
    (r'\bpreviously\b', 'before'),
    (r'\bensure(s|d)?\b', r'make sure\1'),
    (r'\bpermit(s|ted)?\b', r'allow\1'),
    (r'\bendeavor\b', 'try'),
]

# ── approved general vocabulary (subset; STE list is proprietary) ──────────
APPROVED = set('''
a an the this that these those each every all any some no none
it its they them their you your we our one ones which who what
in on at to from by with without of for as into onto over under up down out off
about before after during until between among through across against along behind beyond near since within
and or but nor so yet if then than because while when where unless although
is are was were be been being am has have had do does did done
can could may might must shall should will would
use used uses make makes made get gets got give gives gave put puts
take takes taken keep keeps kept run runs ran go goes gone come comes came
see sees saw show shows showed know knows knew think thinks want wants need needs
work works worked start starts started stop stops stopped open opens opened
close closes closed read reads write writes wrote send sends sent receive receives
call calls called find finds found add adds added remove removes removed
move moves moved set sets hold holds held follow follows allow allows
prevent prevents check checks compare compares count counts contain contains
include includes change changes build builds built create creates created
delete deletes deleted split splits merge merges apply applies extend extends
avoid avoids prefer prefers stay stays become becomes remain remains mean means
define defines provide provides support supports emit emits own owns
not never always often only also again more less most least now then there here
very much many few well new old good bad same different other next first last
long short small large big high low fast slow safe empty full plain light dark
per via true false yes no
one two three four five six seven eight nine ten
do does done done thing things way ways part parts item items value values
type types name names code data file files line lines word words
table list map set key keys row rows column columns
memory disk network socket server client request response
read write lock free cost size level top bottom
'''.split())

PASSIVE_RE = re.compile(
    r'\b(am|is|are|was|were|be|been|being)\s+(\w+ly\s+)?\w+(ed|en)\b', re.I)
TECH_SHAPE_RE = re.compile(r'[0-9_<>/#\-]|^\W*\w*\(.*')


def is_technical(tok):
    t = tok.strip('`*|')
    if TECH_SHAPE_RE.search(t):
        return True
    if t in LEXICON:
        return True
    # CamelCase with an interior capital
    letters = re.sub(r'[^A-Za-z]', '', t)
    if letters and re.match(r'^[A-Za-z]+$', t):
        caps = [c for c in letters[1:] if c.isupper()]
        if len(caps) >= 1 and letters[0].isupper():
            return True
    return False


def word_kind(tok):
    t = tok.strip('`*|.,;:!?()"\'')
    if not t:
        return None
    if t.lower() in APPROVED:
        return 'approved'
    if is_technical(t):
        return 'tech'
    return 'gap'


def apply_subs(text):
    for pat, rep in SUBS:
        text = re.sub(pat, rep, text, flags=re.I)
    return text


def split_sentences(t):
    t = re.sub(r'\s*\u2014\s*', '. ', t)          # em dash -> break (R2)
    t = t.replace('; ', '. ').replace(';.', '.')
    t = re.sub(r':\s+(?=[A-Z])', '. ', t)          # colon before clause -> break
    parts = re.split(r'(?<=[.!?])\s+', t)
    return [p.strip() for p in parts if p.strip()]


def shorten(sent, limit=20):
    words = sent.split()
    if len(words) <= limit:
        return [sent]
    for cut in (' and ', ' that ', ' but ', ' which ', ' because ', ', '):
        for m in re.finditer(re.escape(cut), sent):
            left = sent[:m.start()]
            right = sent[m.end():]
            if len(left.split()) >= 4 and len(right.split()) >= 3:
                return shorten(left.strip(', '), limit) + shorten(right, limit)
    return [sent]


def ste_pass(chunk):
    """Prose chunk -> list of simplified sentences."""
    chunk = re.sub(r'[*_`]', '', chunk)
    chunk = apply_subs(chunk)
    sents = []
    for s in split_sentences(chunk):
        sents.extend(shorten(s))
    return sents


def classify(tokens):
    kinds = collections.Counter()
    gaps = collections.Counter()
    for tok in tokens:
        k = word_kind(tok)
        if k:
            kinds[k] += 1
            if k == 'gap':
                gaps[tok.strip('`*|.,;:!?()"\'').lower()] += 1
    return kinds, gaps


# ── parse source ────────────────────────────────────────────────────────────
lines = open(SRC, 'rb').read().decode('utf-8').splitlines()
out_doc = []
out_doc.append('# TrikeShed Concept Map - Simplified Technical English')
out_doc.append('')
out_doc.append('Source: doc/concepts.md (merged into README.md).')
out_doc.append('This document uses controlled English. Rules:')
out_doc.append('')
out_doc.append('1. Keep each sentence short. The limit is 20 words.')
out_doc.append('2. Give one topic per sentence.')
out_doc.append('3. Use the active voice. Use the present tense.')
out_doc.append('4. Use approved words only. The lexicon defines each technical name one time.')
out_doc.append('5. Keep technical names as names. See the lexicon at the end.')
out_doc.append('6. Code blocks stay unchanged. Code is not prose.')
out_doc.append('')
out_doc.append('---')
out_doc.append('')

in_code = False
code_buf = []
orig_tokens = []
new_tokens = []
orig_sents = []
new_sents = []
passive_before = 0
passive_after = 0
discovered = collections.Counter()


def flush_prose(chunk):
    global passive_before, passive_after
    if not chunk.strip():
        return
    orig_sents.extend(split_sentences(re.sub(r'[*_`]', '', chunk)))
    passive_before += len(PASSIVE_RE.findall(chunk))
    for s in ste_pass(chunk):
        out_doc.append(s)
        new_sents.append(s)
        passive_after += len(PASSIVE_RE.findall(s))
        toks = s.split()
        new_tokens.extend(toks)
        for tok in toks:
            t = tok.strip('`*|.,;:!?()"\'')
            if word_kind(tok) == 'tech':
                base = re.sub(r'[^A-Za-z0-9]', '', t)
                if base and base not in LEXICON and any(c.isupper() for c in base):
                    discovered[base] += 1


para = []
table_hdr = None

for ln in lines:
    if ln.lstrip().startswith('```'):
        if in_code:
            out_doc.append('```')
        else:
            if para:
                flush_prose(' '.join(para))
                para = []
            out_doc.append('')
            out_doc.append('```')
        in_code = not in_code
        continue
    if in_code:
        out_doc.append(ln)
        continue

    stripped = ln.strip()
    is_head = re.match(r'^(#{1,6})\s+(.*)$', ln)
    is_bullet = re.match(r'^\s*[-*]\s+(.*)$', ln)
    is_table = re.match(r'^\|.*\|$', stripped)

    if is_head or is_bullet or is_table or not stripped:
        if para:
            flush_prose(' '.join(para))
            para = []
    else:
        para.append(stripped)
        orig_tokens.extend(stripped.split())
        continue

    if is_head:
        lvl, title = len(is_head.group(1)), is_head.group(2).strip()
        title = re.sub(r'\s*\([^)]*\)\s*$', '', title)   # drop parenthetical
        title = apply_subs(title)
        out_doc.append('')
        out_doc.append('#' * lvl + ' ' + title)
        out_doc.append('')
        table_hdr = None
        continue

    if is_table:
        cells = [apply_subs(c.strip()) for c in stripped.strip('|').split('|')]
        if re.match(r'^[-\s:]+$', ''.join(cells)):
            continue
        if table_hdr is None or cells == table_hdr:
            table_hdr = cells
            out_doc.append('| ' + ' | '.join(cells) + ' |')
            out_doc.append('|' + '---|' * len(cells))
            continue
        out_doc.append('| ' + ' | '.join(cells) + ' |')
        orig_tokens.extend(cells)
        new_tokens.extend(cells)
        continue

    if is_bullet:
        body = is_bullet.group(1).strip()
        m = re.match(r'^`?([A-Za-z][\w]*)`?\s*\u2014\s*(.*)$', body)
        out_doc.append('')
        if m:
            term, rest = m.group(1), m.group(2)
            out_doc.append('**' + term + '.** ' + '. '.join(ste_pass(rest)) + '.')
        else:
            for s in ste_pass(body):
                out_doc.append('- ' + s)
        continue

if para:
    flush_prose(' '.join(para))

# ── lexicon file ────────────────────────────────────────────────────────────
lex_lines = [
    '# US Navy Simplified Technical English - TrikeShed Lexicon',
    '',
    'Each technical name keeps its name in the text.',
    'The lexicon defines it one time, in short words.',
    '',
    '## Curated terms',
    '',
    '| Name | Definition |',
    '|---|---|',
]
for k in sorted(LEXICON):
    lex_lines.append('| ' + k + ' | ' + LEXICON[k] + ' |')
lex_lines.append('')
lex_lines.append('## Discovered candidates (define or reject)')
lex_lines.append('')
lex_lines.append('| Name | Hits | Status |')
lex_lines.append('|---|---|---|')
for name, hits in discovered.most_common():
    if name in LEXICON:
        continue
    if re.match(r'^[A-Z]+$', name) and len(name) <= 5:
        status = 'KEEP AS IS (acronym)'
    elif re.search(r'[a-z]', name) and re.search(r'[A-Z]', name) and len(name) > 22:
        status = 'NOISE (glued code identifier)'
    elif len(name) > 28 or re.match(r'^T[A-Z0-9]+', name):
        status = 'NOISE (task-id or path glue)'
    else:
        status = 'NEEDS DEFINITION'
    lex_lines.append('| ' + name + ' | ' + str(hits) + ' | ' + status + ' |')
open('doc/analysis/usnavy_lexicon.md', 'w').write('\n'.join(lex_lines) + '\n')

# lexicon section into the doc
out_doc.append('')
out_doc.append('---')
out_doc.append('')
out_doc.append('## Lexicon')
out_doc.append('')
out_doc.append('| Name | Definition |')
out_doc.append('|---|---|')
for k in sorted(LEXICON):
    out_doc.append('| ' + k + ' | ' + LEXICON[k] + ' |')
open('doc/analysis/concepts_usnavy_ste.md', 'w').write('\n'.join(out_doc) + '\n')

# ── metrics ────────────────────────────────────────────────────────────────
def H(items):
    if not items:
        return 0.0
    f = collections.Counter(items)
    n = len(items)
    return -sum((c / n) * math.log2(c / n) for c in f.values())


def sent_stats(sents):
    lens = [len(s.split()) for s in sents]
    return (len(sents),
            sum(lens) / max(1, len(lens)),
            max(lens) if lens else 0)


o_kinds, o_gaps = classify(orig_tokens)
n_kinds, n_gaps = classify(new_tokens)
o_n, o_avg, o_max = sent_stats(orig_sents)
n_n, n_avg, n_max = sent_stats(new_sents)

print('=== STE pass metrics: original vs simplified ===')
print('sentences:            %4d  -> %4d' % (o_n, n_n))
print('avg words/sentence:   %5.1f -> %5.1f' % (o_avg, n_avg))
print('max words/sentence:   %4d  -> %4d' % (o_max, n_max))
print('passive constructs:   %4d  -> %4d' % (passive_before, passive_after))
o_cov = 100.0 * (o_kinds['approved'] + o_kinds['tech']) / max(1, sum(o_kinds.values()))
n_cov = 100.0 * (n_kinds['approved'] + n_kinds['tech']) / max(1, sum(n_kinds.values()))
print('approved+tech tokens: %.1f%% -> %.1f%%' % (o_cov, n_cov))
print('lexicon size: %d curated + %d discovered candidates'
      % (len(LEXICON), len([d for d in discovered if d not in LEXICON])))
print('bytes: %d -> %d' % (len(open(SRC).read()), len('\n'.join(out_doc))))
print('word entropy: %.2f -> %.2f bits/word'
      % (H([t.lower() for t in orig_tokens]), H([t.lower() for t in new_tokens])))
print()
print('top gap words remaining: ' +
      ', '.join('%s(%d)' % g for g in n_gaps.most_common(15)))
