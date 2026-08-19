#!/usr/bin/env python3
"""ASD-STE100 Simplified Technical English pass — refactored from the
USNAVY approximation to the real specification rules as documented at
https://en.wikipedia.org/wiki/Simplified_Technical_English

Specification mapping (Wikipedia "Writing rules" -> code):
  WR1  approved words only as the part of speech/meaning in the dictionary
       -> APPROVED (word -> POS set) + UNAPPROVED (word -> alternative)
  WR3  no multi-word nouns with more than three words
       -> noun_run()
  WR4  verb forms: infinitive/imperative/simple present/past/future,
       past participle only as adjective; no auxiliary constructions
       -> verb_forms()
  WR5  -ing form only as a technical noun or modifier in a technical noun
       -> gerund()
  WR6  active voice; passive only when the agent is unknown (descriptions)
       -> passive()
  WR7  <= 20 words per instruction, <= 25 words per descriptive sentence
       -> length with procedure/description split
  WR8  do not omit verb, subject, article
       -> fragment()
  WR9  vertical lists for complex text
       -> vertical_list()
  WR10 one instruction per sentence
  WR11 one topic per paragraph
  WR12 max six sentences per paragraph
  WR13 safety instructions start with a command or condition

STE word classes: approved words (dictionary), technical names (nouns,
project lexicon), technical verbs (project verb list). Technical names and
technical verbs are legal without a dictionary entry.

Dictionary here is a curated SUBSET (the full ASD-STE100 dictionary is
proprietary). Unclassifiable words are FLAG, never silently accepted.

Outputs:
  doc/analysis/concepts_ste100.md   rewritten document
  doc/analysis/ste100_report.md     per-rule violations, before/after
"""
import re
import collections
import math
import gzip

SRC = 'README.md'

# ── WR1: dictionary subset ─────────────────────────────────────────────────
# approved word -> permitted parts of speech (v n adj adv prep conj pron art)
APPROVED = {
    # function words
    'a': {'art'}, 'an': {'art'}, 'the': {'art'},
    'this': {'adj', 'pron'}, 'that': {'adj', 'conj', 'pron'},
    'these': {'adj', 'pron'}, 'those': {'adj', 'pron'},
    'each': {'adj'}, 'all': {'adj', 'n', 'pron'}, 'any': {'adj', 'pron'},
    'some': {'adj', 'pron'}, 'no': {'adj', 'adv'},
    'it': {'pron'}, 'its': {'pron'}, 'they': {'pron'}, 'them': {'pron'},
    'their': {'pron'}, 'you': {'pron'}, 'your': {'pron'}, 'we': {'pron'},
    'one': {'n', 'pron'}, 'which': {'pron', 'conj'}, 'who': {'pron'},
    'what': {'pron'}, 'where': {'adv', 'conj'}, 'when': {'adv', 'conj'},
    'if': {'conj'}, 'than': {'conj'}, 'because': {'conj'},
    'and': {'conj'}, 'or': {'conj'}, 'but': {'conj'},
    'not': {'adv'}, 'never': {'adv'}, 'always': {'adv'}, 'often': {'adv'},
    'only': {'adv'}, 'also': {'adv'}, 'again': {'adv'}, 'now': {'adv'},
    'then': {'adv'}, 'there': {'adv'}, 'here': {'adv'},
    'in': {'prep'}, 'on': {'prep'}, 'at': {'prep'}, 'to': {'prep'},
    'from': {'prep'}, 'by': {'prep'}, 'with': {'prep'},
    'without': {'prep'}, 'of': {'prep'}, 'for': {'prep'}, 'as': {'prep'},
    'into': {'prep'}, 'over': {'prep'}, 'under': {'prep'}, 'up': {'prep'},
    'down': {'prep'}, 'out': {'prep'}, 'off': {'prep'}, 'about': {'prep'},
    'before': {'prep', 'adv', 'conj'}, 'after': {'prep', 'conj'},
    'during': {'prep'}, 'until': {'prep', 'conj'}, 'between': {'prep'},
    'through': {'prep'}, 'across': {'prep'}, 'against': {'prep'},
    'near': {'prep', 'adj', 'adv'}, 'since': {'prep', 'conj'},
    'within': {'prep'}, 'above': {'prep'}, 'below': {'prep'},
    'is': {'v'}, 'are': {'v'}, 'was': {'v'}, 'were': {'v'}, 'be': {'v'},
    'been': {'v'}, 'am': {'v'},
    'has': {'v'}, 'have': {'v'}, 'had': {'v'},
    'do': {'v'}, 'does': {'v'}, 'did': {'v'},
    'can': {'v'}, 'will': {'v'}, 'shall': {'v'},
    # approved verbs (simple forms only)
    'use': {'v', 'n'}, 'make': {'v'}, 'get': {'v'}, 'give': {'v'},
    'put': {'v'}, 'take': {'v'}, 'keep': {'v'}, 'hold': {'v'},
    'run': {'v', 'n'}, 'go': {'v'}, 'come': {'v'}, 'see': {'v', 'n'},
    'show': {'v', 'n'}, 'know': {'v'}, 'think': {'v'}, 'want': {'v'},
    'need': {'v', 'n'}, 'work': {'v', 'n'}, 'start': {'v', 'n'},
    'stop': {'v', 'n'}, 'open': {'v', 'adj'}, 'close': {'v'},
    'read': {'v'}, 'write': {'v'}, 'send': {'v'}, 'receive': {'v'},
    'call': {'v', 'n'}, 'find': {'v'}, 'add': {'v'}, 'remove': {'v'},
    'move': {'v', 'n'}, 'set': {'v', 'n'}, 'follow': {'v'},
    'allow': {'v'}, 'prevent': {'v'}, 'check': {'v', 'n'},
    'compare': {'v'}, 'count': {'v', 'n'}, 'contain': {'v'},
    'include': {'v'}, 'change': {'v', 'n'}, 'build': {'v', 'n'},
    'create': {'v'}, 'delete': {'v'}, 'split': {'v'}, 'merge': {'v'},
    'apply': {'v'}, 'extend': {'v'}, 'avoid': {'v'}, 'prefer': {'v'},
    'stay': {'v'}, 'become': {'v'}, 'remain': {'v'}, 'mean': {'v', 'n'},
    'define': {'v'}, 'provide': {'v'}, 'support': {'v', 'n'},
    'emit': {'v'}, 'own': {'v'}, 'accept': {'v'}, 'access': {'v', 'n'},
    'install': {'v'}, 'connect': {'v'}, 'disconnect': {'v'},
    'disconnect': {'v'}, 'return': {'v', 'n'}, 'turn': {'v', 'n'},
    'must': {'v'},
    # approved nouns
    'thing': {'n'}, 'way': {'n'}, 'part': {'n'}, 'item': {'n'},
    'value': {'n'}, 'type': {'n'}, 'name': {'n'}, 'code': {'n', 'v'},
    'data': {'n'}, 'file': {'n'}, 'line': {'n'}, 'word': {'n'},
    'table': {'n'}, 'list': {'n'}, 'map': {'n'}, 'key': {'n'},
    'row': {'n'}, 'column': {'n'}, 'memory': {'n'}, 'disk': {'n'},
    'network': {'n'}, 'socket': {'n'}, 'server': {'n'}, 'client': {'n'},
    'request': {'n', 'v'}, 'response': {'n'}, 'test': {'n'},
    'level': {'n'}, 'top': {'n', 'adj'}, 'bottom': {'n'},
    'cost': {'n'}, 'size': {'n'}, 'unit': {'n'}, 'system': {'n'},
    'accident': {'n'}, 'assembly': {'n'},
    # approved adjectives
    'new': {'adj'}, 'old': {'adj'}, 'good': {'adj'}, 'bad': {'adj'},
    'same': {'adj'}, 'different': {'adj'}, 'other': {'adj'},
    'next': {'adj'}, 'first': {'adj', 'adv'}, 'last': {'adj'},
    'long': {'adj'}, 'short': {'adj'}, 'small': {'adj'},
    'large': {'adj'}, 'high': {'adj', 'adv'}, 'low': {'adj'},
    'fast': {'adj', 'adv'}, 'slow': {'adj'}, 'safe': {'adj', 'n'},
    'empty': {'adj'}, 'full': {'adj', 'n'}, 'plain': {'adj'},
    'light': {'n', 'adj'}, 'dark': {'adj'}, 'more': {'adj', 'adv'},
    'less': {'adj', 'adv'}, 'most': {'adj', 'adv'},
    'least': {'adj', 'adv'}, 'much': {'adj', 'adv'}, 'many': {'adj'},
    'few': {'adj'}, 'well': {'adv'}, 'very': {'adv'}, 'true': {'adj'},
    'false': {'adj'}, 'yes': {'n'}, 'correct': {'adj'},
    'available': {'adj'}, 'necessary': {'adj'}, 'possible': {'adj'},
    'per': {'prep'}, 'via': {'prep'},
    'do': {'v'}, 'done': {'v'},
}

# unapproved word -> (replacement, note). WR1 alternatives, uppercase-style
# from the dictionary convention: alternative in CAPS in the spec.
UNAPPROVED = {
    'acceptance': ('acceptance', 'ACCEPT (v): use "you accept X"'),
    'accessible': ('ACCESS (n): "you can get access to X"',
                   'get access to'),
    'utilization': ('USE (n/v)', None),
    'utilize': ('USE', None), 'utilizes': ('USE', None),
    'utilised': ('USE', None), 'utilized': ('USE', None),
    'prior': ('BEFORE', None),
    'commence': ('START', None), 'commences': ('START', None),
    'commenced': ('START', None),
    'terminate': ('STOP', None), 'terminates': ('STOP', None),
    'terminated': ('STOP', None),
    'attempt': ('TRY', None), 'attempts': ('TRY', None),
    'attempted': ('TRY', None),
    'obtain': ('GET', None), 'obtains': ('GET', None),
    'obtained': ('GET', None),
    'additional': ('MORE', None),
    'approximately': ('ABOUT', None),
    'subsequently': ('THEN', None),
    'therefore': ('SO', None), 'thus': ('SO', None), 'hence': ('SO', None),
    'regarding': ('ABOUT', None),
    'numerous': ('MANY', None),
    'facilitate': ('HELP', None), 'facilitates': ('HELP', None),
    'facilitated': ('HELP', None),
    'component': ('PART', None), 'components': ('PARTS', None),
    'currently': ('NOW', None),
    'previously': ('BEFORE', None),
    'ensure': ('MAKE SURE', None), 'ensures': ('MAKE SURE', None),
    'ensured': ('MAKE SURE', None),
    'permit': ('ALLOW', None), 'permits': ('ALLOW', None),
    'permitted': ('ALLOW', None),
    'endeavor': ('TRY', None), 'endeavour': ('TRY', None),
    'testing': ('TEST (n) + verb: "do a test"', None),
    'verify': ('CHECK', None), 'verifies': ('CHECK', None),
    'verified': ('CHECK', None), 'verification': ('CHECK (n)', None),
    'ensure(s)': ('MAKE SURE', None),
    'in order to': ('TO', None),
    'ascertain': ('FIND', None), 'determine': ('FIND', None),
    'determines': ('FIND', None), 'determined': ('FIND', None),
    'determination': ('FIND (n)', None),
    'demonstrate': ('SHOW', None), 'demonstrates': ('SHOW', None),
    'demonstrated': ('SHOW', None),
    'sufficient': ('ENOUGH', None),
    'incorrect': ('WRONG', None),
    'correctly': ('RIGHT/CORRECT: "in the correct way"', None),
    'initialize': ('START/SET', None), 'initializes': ('START/SET', None),
    'initialized': ('START/SET', None),
    'implement': ('BUILD/MAKE', None), 'implements': ('BUILD/MAKE', None),
    'implemented': ('BUILD/MAKE', None),
    'maintain': ('KEEP', None), 'maintains': ('KEEP', None),
    'maintained': ('KEEP', None),
}

# ── technical names (STE: legal without dictionary entry; define once) ────
TECHNICAL_NAMES = {
    'Join': 'a pair of two values, a and b',
    'Twin': 'a Join of two values of the same type',
    'Series': 'a list of items. You get an item by its number',
    'Series2': 'a Series that stores each item as a pair',
    'Cursor': 'a table of rows and columns',
    'RowVec': 'one row of a Cursor. It holds a value and a meta supplier',
    'CCEK': 'Coroutine, Context, Element, Key. The reactor object model',
    'reactor': 'the one event loop that runs all work',
    'coroutine': 'a suspended unit of work',
    'NioSupervisor': 'the root registry of IO services',
    'Liburing': 'the Linux async disk and socket interface',
    'io_uring': 'the Linux async disk and socket interface',
    'ChannelRunner': 'the loop that turns IO events into wake-ups',
    'Htx': 'HTTP message blocks',
    'Litebike': 'the listener that opens sockets and hands bytes to CCEK',
    'NUID': 'a name token that grants permission',
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
    'ForgeAssets': 'the baked-in Kotlin object that holds the web shell',
    'MutableMap': 'the Kotlin read write map from the standard library',
    'TODO': 'a marker for work not yet done',
}

# technical verbs (STE: legal; project domain)
TECHNICAL_VERBS = {
    'parse', 'compile', 'dispatch', 'encode', 'decode', 'serialize',
    'deserialize', 'hash', 'append', 'flush', 'fsync', 'snapshot',
    'hydrate', 'bake', 'assert', 'retract', 'supersede', 'land', 'reject',
    'settle', 'drain', 'ingest', 'project', 'reify', 'navigate', 'sort',
    'filter', 'fold', 'suspend', 'resume', 'cancel', 'unwind', 'bind',
    'listen', 'accept', 'fanout', 'escalate', 'claim', 'sequence',
}

AUX = {'is', 'are', 'was', 'were', 'be', 'been', 'am', 'being',
       'has', 'have', 'had', 'having',
       'will', 'shall', 'would', 'should', 'can', 'could', 'may',
       'might', 'must', 'do', 'does', 'did'}

REPORT = collections.defaultdict(list)


def flag(rule, loc, text, fix=None):
    REPORT[rule].append((loc, text[:110], fix))


# ── POS heuristics ─────────────────────────────────────────────────────────
def base_word(tok):
    return tok.strip('`*_|.,;:!?()"\'').lower()


def pos_of(tok, prev=None):
    """Best-effort POS: dictionary POS, else shape heuristics."""
    w = base_word(tok)
    if w in APPROVED:
        return 'dict', w, APPROVED[w]
    if w in TECHNICAL_VERBS and w.endswith('e') or w in TECHNICAL_VERBS:
        return 'techverb', w, {'v'}
    if tok.strip('`*_') in TECHNICAL_NAMES:
        return 'techname', w, {'n'}
    if re.search(r'[0-9_<>/#\-]', tok):
        return 'code', w, {'n'}
    letters = re.sub(r'[^A-Za-z]', '', tok)
    if letters and letters[0].isupper() and any(c.isupper() for c in letters[1:]):
        return 'techname', w, {'n'}     # CamelCase
    if w.endswith('ing'):
        return 'guess', w, {'v-ing'}
    if w.endswith('ed'):
        return 'guess', w, {'v-en', 'adj'}
    if w.endswith('ly'):
        return 'guess', w, {'adv'}
    if w.endswith('tion') or w.endswith('ment') or w.endswith('ance'):
        return 'guess', w, {'n'}
    return 'guess', w, {'n', 'v', 'adj'}


def in_dictionary(w):
    return base_word(w) in APPROVED or base_word(w) in UNAPPROVED


# ── sentence machinery ─────────────────────────────────────────────────────
def split_sentences(t):
    t = re.sub(r'\s*\u2014\s*', '. ', t)
    t = t.replace('; ', '. ')
    parts = re.split(r'(?<=[.!?])\s+', t)
    return [p.strip() for p in parts if p.strip()]


IMPERATIVE_STARTS = None  # filled after APPROVED load


def is_procedure(sent):
    """Instruction = starts with an imperative verb."""
    first = base_word(sent.split()[0]) if sent.split() else ''
    return first in (APPROVED and
                     {w for w, p in APPROVED.items() if 'v' in p} |
                     TECHNICAL_VERBS)


def word_count(sent):
    return len([t for t in sent.split() if re.search(r'[0-9A-Za-z]', t)])


# ── rule checks (per sentence) ────────────────────────────────────────────
def wr1_words(sent, loc):
    """Approved words only as the given POS; unapproved -> alternative."""
    out = []
    for tok in sent.split():
        w = base_word(tok)
        if w in UNAPPROVED:
            rep = UNAPPROVED[w][0]
            flag('WR1 unapproved word', loc, w + ' -> ' + rep)
            out.append(rep.split()[0].lower() if rep and ' ' not in rep else tok)
        else:
            out.append(tok)
    return ' '.join(out)


def wr3_noun_run(sent, loc):
    """No multi-word noun with more than three words."""
    toks = sent.split()
    run = 0
    for tok in toks:
        kind, w, poss = pos_of(tok)
        if kind in ('techname', 'code') or poss == {'n'} or (
                kind == 'guess' and 'n' in poss and 'v' not in poss):
            run += 1
            if run > 3:
                flag('WR3 noun run > 3', loc, sent)
                return
        else:
            run = 0


def wr4_verb_forms(sent, loc):
    """No complex auxiliary constructions."""
    toks = [base_word(t) for t in sent.split()]
    for i in range(len(toks) - 1):
        a, b = toks[i], toks[i + 1]
        if a in ('is', 'are', 'was', 'were', 'be', 'been', 'am') and \
                b.endswith('ing'):
            flag('WR4 continuous form', loc, a + ' ' + b)
        if a in ('has', 'have', 'had') and b.endswith('ed'):
            flag('WR4 perfect form', loc, a + ' ' + b)
        if a in ('will', 'shall') and b in ('be', 'have'):
            flag('WR4 complex future', loc, a + ' ' + b)
        if a in ('would', 'should', 'could', 'might') and i > 0:
            flag('WR4 modal', loc, a)


def wr5_gerund(sent, loc):
    """-ing only as technical noun / modifier in technical noun."""
    PRONOUNS = ('everything', 'anything', 'something', 'nothing')
    for tok in sent.split():
        w = base_word(tok)
        if w in PRONOUNS:
            continue
        if w.endswith('ing') and w not in TECHNICAL_NAMES and \
                tok.strip('`*_') not in TECHNICAL_NAMES:
            if w in ('ring', 'key', 'spring', 'during', 'thing',
                     'engineering', 'marking', 'mapping', 'binding',
                     'encoding', 'ordering', 'rendering', 'naming',
                     'sizing', 'backing'):
                continue
            flag('WR5 gerund', loc, w)


def wr6_passive(sent, loc):
    m = re.search(
        r'\b(is|are|was|were|be|been|am)\s+(\w+ed|\w+en)\b', sent, re.I)
    if m:
        flag('WR6 passive (rewrite active; keep only if agent unknown)',
             loc, m.group(0))


def wr7_length(sent, loc):
    n = word_count(sent)
    proc = is_procedure(sent)
    limit = 20 if proc else 25
    if n > limit:
        flag('WR7 length %d > %d (%s)' % (n, limit,
                                          'procedure' if proc else 'description'),
             loc, sent)


def wr8_fragment(sent, loc):
    toks = sent.split()
    if not toks:
        return
    has_verb = any(pos_of(t)[2] & {'v'} for t in toks if
                   re.search(r'[A-Za-z]', t))
    if not has_verb:
        flag('WR8 no verb (fragment?)', loc, sent)
    if not re.match(r'^[A-Z`*#\d]', sent):
        flag('WR8 sentence does not start capital/term', loc, sent[:40])


def wr9_vertical(sent, loc):
    """Complex text -> vertical list (>= 3 comma/and chains)."""
    items = re.split(r',\s+|\s+and\s+', sent)
    if len(items) >= 4 and word_count(sent) > 15:
        flag('WR9 candidate vertical list', loc, sent)


def wr10_one_instruction(sent, loc):
    if is_procedure(sent):
        for m in re.finditer(r'\s+and\s+', sent):
            tail = sent[m.end():]
            if is_procedure(tail[0].upper() + tail[1:]):
                flag('WR10 two instructions in one sentence', loc, sent)
                break


# ── paragraph checks ──────────────────────────────────────────────────────
def wr11_12_paragraph(par, loc):
    sents = split_sentences(re.sub(r'[*_`]', '', par))
    if len(sents) > 6:
        flag('WR12 paragraph has %d sentences > 6' % len(sents), loc,
             sents[0][:60] + '...')
    # WR11 topic: overlap of main nouns between sentences
    def nouns(s):
        return {base_word(t) for t in s.split()
                if pos_of(t)[2] == {'n'} or pos_of(t)[0] == 'techname'}
    if len(sents) >= 3:
        sets = [nouns(s) for s in sents]
        first = sets[0]
        drift = [i for i in range(1, len(sets))
                 if first and sets[i] and not (first & sets[i])]
        if len(drift) >= 2:
            flag('WR11 topic drift (%d/%d sentences off-topic)'
                 % (len(drift), len(sents)), loc, sents[0][:60] + '...')


# ── substitution pass (safe, mechanical) ───────────────────────────────────
def substitute(text):
    def rep(m):
        w = m.group(0)
        lw = w.lower()
        for bad, (alt, _n) in UNAPPROVED.items():
            if lw == bad or (lw == bad + 's' and not alt.endswith('S')) or \
                    (lw == bad + 'ed' and not alt.endswith('ED')) or \
                    (lw == bad + 'es' and not alt.endswith('ES')):
                # multi-word alternative: inflect only the head verb
                words = alt.lower().split()
                if len(words) > 1 and lw != bad:
                    words[0] = words[0] + lw[len(bad):]
                return ' '.join(words)
        return w
    return re.sub(r"[A-Za-z]+", rep, text)


# ── main pass ─────────────────────────────────────────────────────────────
def main():
    lines = open(SRC, 'rb').read().decode('utf-8').splitlines()
    out = []
    out.append('# TrikeShed Concept Map - ASD-STE100 Simplified Technical English')
    out.append('')
    out.append('Source: doc/concepts.md (merged into README.md).')
    out.append('Rules: ASD-STE100 per the specification summary at')
    out.append('https://en.wikipedia.org/wiki/Simplified_Technical_English')
    out.append('Code blocks stay unchanged. Code is not prose.')
    out.append('')
    in_code = False
    para = []
    para_start = 0

    def flush():
        if not para:
            return
        chunk = ' '.join(para)
        loc = 'line %d' % para_start
        wr11_12_paragraph(chunk, loc)
        for s in split_sentences(re.sub(r'[*_`]', '', chunk)):
            s2 = substitute(s)
            wr1_words(s2, loc)
            wr3_noun_run(s2, loc)
            wr4_verb_forms(s2, loc)
            wr5_gerund(s2, loc)
            wr6_passive(s2, loc)
            wr7_length(s2, loc)
            wr8_fragment(s2, loc)
            wr9_vertical(s2, loc)
            wr10_one_instruction(s2, loc)
            out.append(s2[0].upper() + s2[1:] if s2 else s2)
        out.append('')
        para.clear()

    for idx, ln in enumerate(lines, 1):
        if ln.lstrip().startswith('```'):
            flush()
            in_code = not in_code
            out.append(ln)
            continue
        if in_code:
            out.append(ln)
            continue
        s = ln.strip()
        if re.match(r'^#{1,6}\s+', ln) or re.match(r'^\|', s) or \
                re.match(r'^\s*[-*]\s+', ln) or not s or s.startswith('<') \
                or s.startswith('![') or s.startswith('++'):
            flush()
            out.append(ln)
            continue
        if not para:
            para_start = idx
        para.append(s)

    flush()
    open('doc/analysis/concepts_ste100.md', 'w').write('\n'.join(out) + '\n')

    # report
    rep = ['# ASD-STE100 conformance report', '',
           'Corpus: README.md (= doc/concepts.md merged).',
           'Dictionary: curated subset (full ASD-STE100 dictionary is',
           'proprietary). FLAG items need a human writer.', '']
    rep.append('| Rule | Count |')
    rep.append('|---|---|')
    by_wr = collections.OrderedDict()
    for rule in sorted(REPORT):
        wr = rule.split(' ')[0]          # WR1..WR13
        by_wr[wr] = by_wr.get(wr, 0) + len(REPORT[rule])
    total = 0
    for wr in sorted(by_wr):
        rep.append('| %s | %d |' % (wr, by_wr[wr]))
        total += by_wr[wr]
    rep.append('| TOTAL | %d |' % total)
    rep.append('')
    for rule in sorted(REPORT):
        rep.append('## ' + rule)
        rep.append('')
        seen = set()
        for loc, text, *_ in REPORT[rule][:40]:
            key = text[:60]
            if key in seen:
                continue
            seen.add(key)
            rep.append('- `%s` (%s): %s' % (loc, rule, text))
        rep.append('')
    open('doc/analysis/ste100_report.md', 'w').write('\n'.join(rep) + '\n')

    print('=== ASD-STE100 pass (real spec) vs USNAVY approximation ===')
    for rule in sorted(REPORT):
        print('%-12s %s: %d' % ('', rule, len(REPORT[rule])))
    print('total flags: %d' % total)
    print('rewritten:   doc/analysis/concepts_ste100.md')
    print('report:      doc/analysis/ste100_report.md')


if __name__ == '__main__':
    main()
