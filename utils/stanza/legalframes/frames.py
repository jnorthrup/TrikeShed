"""Grammar rules over Stanza's Universal Dependencies parse: legal frame candidates.

Rule-based and deterministic: each frame names the rule that produced it, the dependency edges
it read, and what it leaves unresolved. Nothing here establishes legal force, scope or meaning;
that matches TrikeShed's EnglishLegalPatterns (modal/aux role candidates, bounded quantity
readings), whose modal list and unresolved labels these rules reuse, extended to grants,
assents, definitions, copular status and property events.
"""
from __future__ import annotations

import re
from collections import defaultdict

MODALS = {'shall', 'must', 'may', 'might', 'should', 'can', 'cannot', 'could', 'will', 'would', 'ought'}
NEGATORS = {'not', 'never', "n't", 'nt'}
# Predicates that carry a norm through a copula or passive: "is required to", "are responsible for".
NORM_PREDICATES = {'required': 'obligation', 'obligated': 'obligation', 'obliged': 'obligation',
                   'responsible': 'obligation', 'liable': 'liability', 'entitled': 'permission',
                   'permitted': 'permission', 'authorized': 'permission', 'authorised': 'permission',
                   'allowed': 'permission', 'prohibited': 'prohibition', 'forbidden': 'prohibition',
                   'eligible': 'eligibility', 'presumed': 'presumption', 'deemed': 'deeming',
                   'implied': 'implication', 'excluded': 'exclusion'}
NEGATED_NORM = {'obligation': 'no-obligation', 'liability': 'no-liability', 'permission': 'prohibition',
                'prohibition': 'no-prohibition', 'eligibility': 'ineligibility'}
GRANT_VERBS = {'authorize', 'authorise', 'permit', 'allow', 'grant', 'license', 'instruct', 'direct',
               'empower', 'appoint', 'entitle'}
CONVEY_VERBS = {'grant', 'license', 'assign', 'convey'}
ASSENT_VERBS = {'agree', 'consent', 'acknowledge', 'understand', 'accept', 'represent', 'warrant',
                'certify', 'attest', 'confirm', 'affirm', 'release', 'waive', 'indemnify', 'promise',
                'undertake', 'covenant'}
PROPERTY_VERBS = {'own', 'possess', 'hold', 'control', 'belong', 'store', 'safeguard', 'keep', 'retain',
                  'detain', 'lend', 'loan', 'pledge', 'rehypothecate', 'transfer', 'deliver', 'withdraw',
                  'debit', 'credit', 'deposit', 'send', 'receive', 'return', 'convert', 'sell', 'purchase',
                  'buy', 'gain', 'acquire', 'freeze', 'suspend', 'seize', 'liquidate', 'redeem', 'stake',
                  'custody', 'disclose', 'share', 'collect', 'use', 'process', 'order', 'obtain', 'sign',
                  'grant', 'assign', 'convey'}
PROPERTY_NOUNS = {'owner', 'ownership', 'title', 'custody', 'custodian', 'possession', 'control', 'holder',
                  'beneficiary', 'trustee', 'fiduciary', 'property', 'entitlement', 'claim', 'right',
                  'lien', 'interest', 'authorization', 'consent', 'signature'}
GUARD_MARKS = {'if': 'condition', 'when': 'condition', 'whenever': 'condition', 'where': 'condition',
               'once': 'condition', 'provided': 'condition', 'providing': 'condition', 'unless': 'exception',
               'except': 'exception', 'until': 'temporal', 'before': 'temporal', 'after': 'temporal',
               'while': 'temporal', 'upon': 'condition'}
GUARD_CASES = {'except': 'exception', 'notwithstanding': 'override', 'without': 'exception',
               'subject': 'override', 'upon': 'condition', 'until': 'temporal', 'during': 'temporal'}
UNITS = {'day': 'day', 'days': 'day', 'week': 'week', 'weeks': 'week', 'month': 'month', 'months': 'month',
         'year': 'year', 'years': 'year', 'hour': 'hour', 'hours': 'hour', 'minute': 'minute', 'minutes': 'minute'}
NUMBER_WORDS = {w: i for i, w in enumerate(
    'zero one two three four five six seven eight nine ten eleven twelve thirteen fourteen fifteen sixteen '
    'seventeen eighteen nineteen'.split())}
NUMBER_WORDS.update({w: (i + 2) * 10 for i, w in enumerate('twenty thirty forty fifty sixty seventy eighty ninety'.split())})
CORE = {'det', 'amod', 'compound', 'flat', 'nmod:poss', 'nummod', 'fixed', 'compound:prt', 'det:poss'}
SUBJECTS = ('nsubj', 'nsubj:pass', 'nsubj:outer', 'csubj', 'csubj:pass')
OBJECTS = ('obj', 'iobj')
ALIAS_GLUE = re.compile(r'^(?:\s|,|or|and|also|referred|to|herein|hereinafter|as|collectively|each|together|'
                        r'individually|the|a|an|“|”|"|’|‘)*$', re.I)


NEG_FRONT = {('in', 'event'), ('in', 'case'), ('under', 'circumstance'), ('at', 'time')}


class Sentence:
    def __init__(self, record: dict, text: str):
        self.r = record
        self.text = text
        self.w = {w['id']: dict(w) for w in record['words'] if isinstance(w['id'], int)}
        self.neg_extra: set[int] = set()
        self._index()
        self.repairs = self._repair_negative_inversion()

    def _index(self):
        self.kids: dict[int, list[int]] = defaultdict(list)
        for w in self.w.values():
            self.kids[w['head']].append(w['id'])

    def _repair_negative_inversion(self):
        """“IN NO EVENT SHALL COINBASE ... BE LIABLE”: after a fronted negative phrase the subject follows
        the modal, and the tagger reads it as a verb heading the modal. Re-attach it as the subject of the
        predicate the parser found, coordinate its stray objects with it, and carry the fronted “no” as
        the clause's negation. The repair is recorded on every frame of the sentence."""
        ids = sorted(self.w)
        if len(ids) < 6:
            return []
        t = [self.low(i) for i in ids[:5]]
        if not (t[1] == 'no' and (t[0], self.lem(ids[2]).rstrip('s')) in NEG_FRONT and self.lem(ids[3]) in MODALS):
            return []
        modal, x = ids[3], ids[4]
        w = self.w
        p = w[x]['head']
        if w[modal]['head'] != x or w[x]['upos'] not in ('VERB', 'AUX') or p == 0 or p < x:
            return []  # parsed as an inversion already, or another shape
        proper = w[x]['ner'] != 'O' or w[x]['text'][:1].isupper()
        w[x].update(upos='PROPN' if proper else 'NOUN', xpos='NNP' if proper else 'NN', deprel='nsubj', head=p,
                    lemma=w[x]['text'] if proper else w[x]['lemma'])
        w[modal]['head'] = p
        for k in list(self.kids.get(x, ())):
            if w[k]['deprel'] == 'obj':
                w[k]['deprel'] = 'conj'
            elif w[k]['deprel'] in ('obl', 'advmod') and k < x:
                w[k]['head'] = p
        self.neg_extra.add(p)
        self._index()
        return [{'rule': 'negative-inversion', 'subject': w[x]['text'], 'modal': w[modal]['text'],
                 'predicate': w[p]['text'], 'fronted': ' '.join(w[i]['text'] for i in ids[:3])}]

    def low(self, i):
        return self.w[i]['text'].lower()

    def lem(self, i):
        return (self.w[i]['lemma'] or self.w[i]['text']).lower()

    def rel(self, i):
        return self.w[i]['deprel']

    def ch(self, i, *rels):
        return [k for k in self.kids.get(i, ()) if self.w[k]['deprel'] in rels]

    def subtree(self, i, skip=()):
        out, stack = [i], [i]
        while stack:
            for k in self.kids.get(stack.pop(), ()):
                if self.w[k]['deprel'] not in skip:
                    out.append(k); stack.append(k)
        return sorted(out)

    def quote(self, ids):
        ids = [i for i in ids if self.w[i]['upos'] != 'PUNCT'] or ids
        b = min(self.w[i]['begin'] for i in ids); e = max(self.w[i]['end'] for i in ids)
        return b, e, self.text[b:e]

    def negated(self, i):
        return i in self.neg_extra or any(self.lem(k) in NEGATORS or self.low(k) in NEGATORS for k in self.ch(i, 'advmod', 'neg', 'aux', 'det'))

    def core_ids(self, i):
        out, stack = [i], [i]
        while stack:
            for k in self.ch(stack.pop(), *CORE):
                out.append(k); stack.append(k)
        return sorted(out)

    def node(self, i, full=False):
        w = self.w[i]
        b, e, q = self.quote(self.subtree(i, skip=('punct',)) if full else self.core_ids(i))
        mods = [k for k in self.ch(i, 'compound', 'amod', 'flat') if k < i]
        kept = [k for k in sorted(mods) if self.w[k]['upos'] in ('NOUN', 'PROPN', 'ADJ', 'VERB', 'X')]
        compound = [self.lem(k) for k in kept]
        surface = [self.low(k) for k in kept]
        tail = []
        for k in sorted(self.ch(i, 'nmod')):
            c = self.ch(k, 'case')
            if k > i and c and not self.ch(k, 'nmod', 'acl', 'acl:relcl'):
                tail = [self.lem(c[0]), self.lem(k)]
                break
        acl = [self.lem(k) for k in self.ch(i, 'acl', 'xcomp') if any(self.low(m) == 'to' for m in self.ch(k, 'mark'))]
        return {'id': i, 'text': w['text'], 'lemma': self.lem(i), 'upos': w['upos'], 'xpos': w['xpos'],
                'feats': w['feats'], 'ner': (w['ner'] or 'O')[2:] if (w['ner'] or 'O') != 'O' else None,
                'quote': q, 'begin': b, 'end': e, 'compound': compound + [self.lem(i)],
                'compoundUpos': [self.w[k]['upos'] for k in kept] + [w['upos']], 'surface': surface + [self.low(i)],
                'tail': tail, 'acl': acl,
                'det': [self.lem(k) for k in self.ch(i, 'det')]}

    def with_conj(self, ids):
        """Coordinated nouns share their first conjunct's role: “of all obligations; of ... rights”."""
        out = []
        for i in ids:
            out.append(i)
            stack = [i]
            while stack:
                for k in self.ch(stack.pop(), 'conj'):
                    if self.w[k]['upos'] in ('NOUN', 'PROPN') and k not in out:
                        out.append(k); stack.append(k)
        return out

    def verb_lemma(self, v):
        prt = [self.low(k) for k in self.ch(v, 'compound:prt')]
        return '_'.join([self.lem(v)] + prt)

    def of_series(self, i):
        """“of all inanimate things ...; of all domestic animals; of all obligations”: Stanza nests each
        semicolon-separated of-phrase under the one before; read the chain as one list."""
        out, cur = [i], i
        while True:
            nxt = None
            for k in self.ch(cur, 'nmod', 'conj'):
                c = self.ch(k, 'case')
                if c and self.low(c[0]) == 'of' and (c[0] - 1) in self.w and self.w[c[0] - 1]['text'] in (';', ','):
                    nxt = k
            if nxt is None or nxt in out:
                return out
            out.append(nxt); cur = nxt

    def edge(self, i):
        return [self.w[i]['head'], i, self.w[i]['deprel']]

    def modal_of(self, v):
        """The modal auxiliary governing v, inherited along coordination when v has none."""
        for k in self.ch(v, 'aux'):
            if self.w[k]['xpos'] == 'MD' or self.lem(k) in MODALS or self.low(k) in MODALS:
                return k, False
        if self.rel(v) == 'conj' and self.w[v]['head'] in self.w and not self.ch(v, *SUBJECTS):
            found = self.modal_of(self.w[v]['head'])
            if found:
                return found[0], True
        return None

    def antecedent(self, k):
        """A relative pronoun subject (who, which, that) stands for the noun its clause modifies."""
        w = self.w[k]
        if 'PronType=Rel' in (w['feats'] or '') or (w['upos'] == 'PRON' and self.low(k) in ('who', 'which', 'that', 'whom')):
            v = w['head']
            if v in self.w and self.rel(v) in ('acl:relcl', 'acl') and self.w[v]['head'] in self.w:
                return self.w[v]['head'], True
        return k, False

    def subjects(self, v):
        own = self.ch(v, *SUBJECTS)
        if own:
            resolved = [self.antecedent(k) for k in own]
            return [k for k, _ in resolved], any(r for _, r in resolved)
        if self.rel(v) in ('conj', 'xcomp') and self.w[v]['head'] in self.w:
            inherited, _ = self.subjects(self.w[v]['head'])
            if self.rel(v) == 'xcomp':
                objs = self.ch(self.w[v]['head'], *OBJECTS)
                if objs:  # object control: "authorize Coinbase to initiate"
                    return objs, True
            return inherited, True
        return [], False

    def passive(self, v):
        if self.ch(v, 'aux:pass', 'nsubj:pass', 'csubj:pass'):
            return True
        # Stanza sometimes labels “may be restricted” aux + nsubj; a participle under “be” is still passive
        return self.w[v]['xpos'] == 'VBN' and any(self.lem(k) == 'be' for k in self.ch(v, 'aux'))

    def case_of(self, i):
        cases = self.ch(i, 'case', 'mark')
        if not cases:
            return None
        k = cases[0]
        fixed = [self.low(f) for f in self.ch(k, 'fixed')]
        return ' '.join([self.low(k)] + fixed)

    def obliques(self, v):
        out = []
        for k in self.ch(v, 'obl', 'obl:agent', 'obl:tmod', 'obl:npmod', 'nmod', 'advcl'):
            c = self.case_of(k)
            if c is None and self.rel(k) == 'advcl':
                continue
            out.append({'case': c, 'relation': self.rel(k), 'node': self.node(k), 'edge': self.edge(k)})
        return out

    def guards(self, v):
        out = []
        for k in self.ch(v, 'advcl', 'obl', 'acl'):
            marks = [m for m in self.ch(k, 'mark', 'case', 'advmod') if self.low(m) in GUARD_MARKS or self.low(m) in GUARD_CASES]
            for m in marks:
                kind = GUARD_MARKS.get(self.low(m)) if self.rel(m) in ('mark', 'advmod') else GUARD_CASES.get(self.low(m))
                if kind is None:
                    continue
                b, e, q = self.quote(self.subtree(k, skip=('punct',)))
                out.append({'kind': kind, 'marker': self.w[m]['text'], 'quote': q, 'begin': b, 'end': e,
                            'edges': [self.edge(k), self.edge(m)]})
        return out


def _number(literal_tokens):
    digits = [t for t in literal_tokens if t.isdigit()]
    words = [NUMBER_WORDS[t.lower()] for t in literal_tokens if t.lower() in NUMBER_WORDS]
    vals = {int(d) for d in digits} | ({sum(words)} if words else set())
    return (vals.pop(), None) if len(vals) == 1 else (None, 'conflicting-number-forms' if vals else 'unsupported-number-language')


def durations(s: Sentence):
    out = []
    for i, w in s.w.items():
        unit = UNITS.get(s.low(i)) or UNITS.get(s.lem(i))
        if not unit:
            continue
        nums = s.ch(i, 'nummod')
        if not nums:
            continue
        lo = min(nums + [k for k in s.ch(i, 'amod', 'compound') if k < i])
        span_ids = [k for k in range(lo, i + 1) if k in s.w]
        mods = [s.lem(k) for k in s.ch(i, 'amod', 'compound') if s.lem(k) in ('business', 'calendar', 'working')]
        value, issue = _number([s.w[k]['text'].strip('()') for k in span_ids])
        marker = next((s.w[k]['text'] for k in s.ch(i, 'case') if s.low(k) in ('within', 'before', 'after', 'by', 'until', 'for', 'of')), None)
        b, e, q = s.quote(span_ids + s.ch(i, 'case'))
        out.append({'unit': unit, 'value': value, 'modifiers': mods, 'marker': marker, 'quote': q, 'begin': b,
                    'end': e, 'attachedTo': w['head'], 'issue': issue,
                    'authority': 'bounded-lexical-reading-only'})
    return out


def _attach_durations(s, frames, spans):
    for d in spans:
        head = d['attachedTo']
        chain = set()
        while head and head in s.w and head not in chain:
            chain.add(head)
            for f in frames:
                if f['head'] == head:
                    f.setdefault('durations', []).append(d)
                    d['frame'] = f['rule']
            if 'frame' in d:
                break
            head = s.w[head]['head']


def modal_readings(modal: str, negated: bool) -> list[str]:
    m = modal.lower()
    if m in ('shall', 'must'):
        return ['prohibition'] if negated else ['obligation']
    if m == 'may':
        return ['prohibition'] if negated else ['permission', 'possibility']
    if m == 'cannot' or (m == 'can' and negated):
        return ['prohibition', 'inability']
    if m == 'can':
        return ['permission', 'possibility']
    if m == 'could':
        return ['possibility']
    if m == 'will':
        return ['negative-commitment', 'prediction'] if negated else ['commitment', 'prediction']
    if m == 'would':
        return ['conditional']
    if m in ('should', 'ought'):
        return ['discouragement'] if negated else ['recommendation']
    return ['possibility']


def unresolved_base(s: Sentence) -> list[str]:
    out = ['legal-force-not-established', 'cross-sentence-and-paragraph-scope-not-established']
    if any(w['deprel'].startswith('conj') or w['deprel'] == 'cc' for w in s.w.values()):
        out.append('coordination-scope-not-established')
    return out


def leading_alias(record: dict, prev: dict | None, text: str) -> list[dict]:
    """“... and Coinbase, Inc.” | “(“Coinbase,” “we,” “us,” and “our”). This ...”: the parenthesis opens a
    sentence, so its antecedent is the last noun phrase of the sentence before."""
    m = re.match(r'\s*\(([^()]{1,160})\)', record['quote'])
    if not m or prev is None:
        return []
    inner = m.group(1)
    aliases = [a.strip(' ,') for a in re.findall(r'[“"]\s*([^“”"]+?)\s*,?\s*[”"]', inner)]
    if not aliases or not ALIAS_GLUE.match(re.sub(r'[“"][^“”"]+[”"]', ' ', inner)):
        return []
    s = Sentence(prev, text)
    nouns = [i for i in s.w if s.w[i]['upos'] in ('PROPN', 'NOUN')]
    if not nouns:
        return []
    head = nouns[-1]
    while s.rel(head) in ('compound', 'flat', 'amod', 'det', 'appos', 'list', 'nmod:desc') and s.w[head]['head'] in s.w \
            and s.w[s.w[head]['head']]['upos'] in ('PROPN', 'NOUN'):
        head = s.w[head]['head']
    ids = s.subtree(head, skip=('punct', 'acl', 'acl:relcl', 'parataxis', 'case', 'cc', 'conj'))
    b, e, q = s.quote(ids)
    node = s.node(head)
    return [{'rule': 'alias', 'sentence': record['index'], 'section': record.get('section'), 'head': head,
             'headNode': node, 'quote': prev['quote'] + ' ' + record['quote'][:m.end()], 'begin': prev['begin'],
             'end': record['begin'] + m.end(), 'u16Begin': prev['u16Begin'], 'u16End': record['u16Begin'] + m.end(),
             'guards': [], 'negated': False, 'unresolved': ['antecedent-in-previous-sentence'],
             'term': q.rstrip(' ,.'), 'aliases': aliases, 'aliasBegin': record['begin'] + m.start(1) - 1,
             'aliasEnd': record['begin'] + m.end()}]


def frames(record: dict, text: str) -> list[dict]:
    s = Sentence(record, text)
    base = unresolved_base(s)
    out: list[dict] = []

    def frame(rule, head, **kw):
        f = {'rule': rule, 'sentence': record['index'], 'section': record.get('section'), 'head': head,
             'headNode': s.node(head), 'quote': record['quote'], 'begin': record['begin'], 'end': record['end'],
             'u16Begin': record['u16Begin'], 'u16End': record['u16End'], 'guards': s.guards(head),
             'negated': s.negated(head), 'unresolved': list(base)}
        if s.repairs:
            f['parseRepairs'] = s.repairs
        f.update(kw)
        if f['guards']:
            f['unresolved'].append('guard-scope-not-established')
        if f['negated']:
            f['unresolved'].append('negation-scope-not-established')
        out.append(f)
        return f

    def roles(v):
        subs, inherited = s.subjects(v)
        r = {'subjects': [s.node(k) for k in subs], 'objects': [s.node(k) for k in s.ch(v, *OBJECTS)],
             'obliques': s.obliques(v), 'passive': s.passive(v)}
        xc = s.ch(v, 'xcomp')
        if xc:
            r['complement'] = {'node': s.node(xc[0]), 'passive': s.passive(xc[0]), 'full': s.node(xc[0], full=True)['quote'],
                               'objects': [s.node(k) for k in s.ch(xc[0], *OBJECTS)], 'obliques': s.obliques(xc[0])}
        cc = s.ch(v, 'ccomp')
        if cc:
            r['content'] = s.node(cc[0], full=True)
        return r, inherited

    # R1: modal auxiliary + action (EnglishLegalPatterns' modal/aux roles, with coordination inheritance).
    for v in s.w:
        found = s.modal_of(v)
        if not found:
            continue
        m, inherited_modal = found
        r, inherited_subject = roles(v)
        neg = s.negated(v) or s.low(m) == 'cannot'
        f = frame('modal-action', v, modal=s.w[m]['text'], modalLemma=s.lem(m),
                  readings=modal_readings(s.low(m), neg), edges=[s.edge(m)], **r)
        f['negated'] = neg
        if inherited_modal:
            f['unresolved'].append('coordination-modal-inherited')
        if inherited_subject:
            f['unresolved'].append('subject-inherited-from-head')
        if s.ch(v, 'cop'):
            f['copular'] = True

    # R2: norm-bearing predicates ("is required to", "are responsible for", "reserve the right to").
    for p in s.w:
        low = s.low(p)
        if low in NORM_PREDICATES and (s.ch(p, 'cop') or s.ch(p, 'aux:pass') or s.rel(p) in ('xcomp', 'amod', 'acl')):
            r, inh = roles(p)
            gov = s.w[p]['head']
            neg = s.negated(p) or (s.rel(p) == 'xcomp' and gov in s.w and s.negated(gov))
            reading = NORM_PREDICATES[low]
            if neg:
                reading = NEGATED_NORM.get(reading, reading)
            f = frame('norm-predicate', p, reading=reading, predicate=s.w[p]['text'], **r)
            f['negated'] = neg
            if inh:
                f['unresolved'].append('subject-inherited-from-head')
        if s.lem(p) == 'need' and s.w[p]['upos'] == 'VERB' and s.ch(p, 'xcomp'):
            r, inh = roles(p)
            f = frame('norm-predicate', p, reading='prohibition' if s.negated(p) else 'obligation', predicate='need to', **r)
            if inh:
                f['unresolved'].append('subject-inherited-from-head')
        if s.lem(p) in ('reserve', 'have', 'retain') and any(s.lem(o) == 'right' for o in s.ch(p, 'obj')):
            r, inh = roles(p)
            right = next(o for o in s.ch(p, 'obj') if s.lem(o) == 'right')
            acts = s.ch(right, 'acl', 'acl:relcl', 'xcomp')
            if acts:
                r['complement'] = {'node': s.node(acts[0]), 'passive': s.passive(acts[0]), 'full': s.node(acts[0], full=True)['quote'],
                                   'objects': [s.node(k) for k in s.ch(acts[0], *OBJECTS)], 'obliques': s.obliques(acts[0])}
            frame('norm-predicate', p, reading='permission', predicate=f'{s.w[p]["text"]} the right', **r)

    # R3: grants and assents ("I authorize Contoso to order", "you agree that", "you consent to").
    for v in s.w:
        lem = s.lem(v)
        if s.w[v]['upos'] != 'VERB' or lem not in GRANT_VERBS | ASSENT_VERBS:
            continue
        r, inh = roles(v)
        if not r['subjects'] and s.rel(v) not in ('root',):
            continue
        if lem in CONVEY_VERBS and 'complement' not in r:
            continue  # “One who grants a thing”: a conveyance, read by the property-event rule
        if lem == 'represent' and not s.ch(v, 'ccomp') and not any(s.lem(k) == 'warrant' for k in s.ch(v, 'conj')):
            continue
        f = frame('grant' if lem in GRANT_VERBS else 'assent', v, verb=s.w[v]['text'], verbLemma=lem, **r)
        if inh:
            f['unresolved'].append('subject-inherited-from-head')
        if lem == 'agree' and 'complement' in r:
            f['reading'] = 'prohibition' if s.negated(s.ch(v, 'xcomp')[0]) else 'obligation'
        elif lem in GRANT_VERBS:
            f['reading'] = 'permission'
        else:
            f['reading'] = 'assent'

    # R4: definitions: "“X” means Y", "X refers to Y", parenthetical aliases (“Coinbase,” “we,” or “us”).
    for v in s.w:
        if s.lem(v) in ('mean', 'refer') and s.w[v]['upos'] == 'VERB':
            subs = s.ch(v, 'nsubj')
            if not subs:
                continue
            term_b, term_e, term_q = s.quote(s.subtree(subs[0], skip=('punct',)))
            term = term_q.strip('“”"\' \n')
            body = (s.ch(v, 'obj') or [o['node']['id'] for o in s.obliques(v) if o['case'] == 'to'])
            if not body:
                continue
            genus = [s.node(body[0])]
            for rc in s.ch(body[0], 'acl:relcl', 'acl'):
                if s.ch(rc, 'cop'):
                    genus.append(s.node(rc))
            frame('definition', v, term=term, termBegin=term_b, termEnd=term_e,
                  definiens=s.node(body[0], full=True), genus=genus)
    for v in s.w:
        if s.lem(v) == 'call' and s.passive(v):
            named = s.ch(v, 'xcomp', 'obj')
            subs = s.ch(v, 'nsubj:pass')
            if named and subs:
                b, e, q = s.quote(s.subtree(named[0], skip=('punct',)))
                frame('definition', v, term=q.strip('“”"\' '), termBegin=b, termEnd=e,
                      definiens=s.node(subs[0], full=True), genus=[s.node(subs[0])])
    for m in re.finditer(r'\(([^()]{1,160})\)', record['quote']):
        inner = m.group(1)
        aliases = [a.strip(' ,') for a in re.findall(r'[“"]\s*([^“”"]+?)\s*,?\s*[”"]', inner)]
        if not aliases or not ALIAS_GLUE.match(re.sub(r'[“"][^“”"]+[”"]', ' ', inner)):
            continue
        open_at = record['begin'] + m.start()
        before = [i for i in s.w if s.w[i]['end'] <= open_at and s.w[i]['upos'] != 'PUNCT']
        if not before:
            continue
        head = before[-1]
        while s.rel(head) in ('compound', 'flat', 'amod', 'det') and s.w[head]['head'] in s.w:
            head = s.w[head]['head']
        ids = [i for i in s.subtree(head, skip=('punct', 'appos', 'acl', 'acl:relcl', 'parataxis', 'case', 'cc'))
               if s.w[i]['end'] <= open_at]
        if not ids:
            continue
        b, e, q = s.quote(ids)
        frame('alias', head, term=q, aliases=aliases, aliasBegin=open_at, aliasEnd=record['begin'] + m.end())

    # R5: copular status ("Coinbase is a securities intermediary", "One who ... is an involuntary trustee").
    for p in s.w:
        cops = s.ch(p, 'cop')
        if not cops or s.low(p) in NORM_PREDICATES:
            continue
        subs, inh = s.subjects(p)
        if not subs:
            continue
        rc = [{'node': s.node(k), 'full': s.node(k, full=True)['quote'], 'objects': [s.node(o) for o in s.ch(k, *OBJECTS)],
               'modifiers': [s.node(a) for a in s.ch(k, 'advmod')]} for k in s.ch(subs[0], 'acl:relcl', 'acl')]
        f = frame('copular', p, subject=s.node(subs[0]), subjectFull=s.node(subs[0], full=True)['quote'],
                  predicateCase=s.case_of(p),
                  subjectClauses=rc, predicate=s.node(p), predicateModifiers=[s.node(k) for k in s.ch(p, 'amod', 'compound')],
                  obliques=s.obliques(p), modal=(lambda m: s.w[m[0]]['text'] if m else None)(s.modal_of(p)))
        if inh:
            f['unresolved'].append('subject-inherited-from-head')
    for v in s.w:
        if s.lem(v) == 'treat' and s.ch(v, 'obj'):
            as_args = [o for o in s.obliques(v) if o['case'] == 'as']
            if as_args:
                r, _ = roles(v)
                frame('treat-as', v, theme=s.node(s.ch(v, 'obj')[0]), asClass=as_args[0]['node'], **r)

    # R6: property events and relations (own, hold, title to, custody of, owner of, transfer, debit, detain).
    for v in s.w:
        lem = s.lem(v)
        if s.w[v]['upos'] == 'VERB' and lem in PROPERTY_VERBS:
            lem = s.verb_lemma(v)
            r, inh = roles(v)
            agent = r['subjects'] if not r['passive'] else [o['node'] for o in r['obliques'] if o['case'] == 'by']
            theme = r['objects'] if not r['passive'] else r['subjects']
            theme = theme + [s.node(k) for k in s.with_conj([t['id'] for t in theme]) if k not in {t['id'] for t in theme}]
            f = frame('property-event', v, verb=s.w[v]['text'], verbLemma=lem, agent=agent, theme=theme,
                      modal=(lambda m: s.w[m[0]]['text'] if m else None)(s.modal_of(v)), **r)
            if inh:
                f['unresolved'].append('subject-inherited-from-head')
        elif s.w[v]['upos'] in ('NOUN', 'PROPN') and lem in PROPERTY_NOUNS:
            args = [o for o in s.obliques(v) if o['case'] in ('of', 'to', 'over', 'in', 'for')]
            for o in list(args):
                for k in s.with_conj(s.of_series(o['node']['id']))[1:]:
                    args.append({'case': s.case_of(k) or o['case'], 'relation': 'series', 'node': s.node(k), 'edge': s.edge(k)})
            holders = [s.node(k) for k in s.ch(v, 'nmod:poss')]
            governor = None
            if s.rel(v) in ('nsubj', 'nsubj:pass', 'obj') and s.w[v]['head'] in s.w:
                g = s.w[v]['head']
                governor = {'node': s.node(g), 'relation': s.rel(v), 'obliques': s.obliques(g),
                            'modal': (lambda m: s.w[m[0]]['text'] if m else None)(s.modal_of(g)), 'negated': s.negated(g)}
            if not args and not holders:
                continue
            frame('property-relation', v, noun=s.w[v]['text'], nounLemma=lem, holders=holders, arguments=args,
                  governor=governor)

    _attach_durations(s, out, durations(s))
    return out
