"""Frames to typed SUMO candidates, checked against SUMO's own domain and disjointness axioms.

Typing is lexical and rule-based, in this order: party aliases the document itself defines
(“Coinbase,” “we,” or “us”), Stanza's named-entity type, then WordNet 3.0 senses through SUMO's
WordNet mappings, trying the surface form before the lemma (“assets” and “asset” are different
WordNet entries, and the lemma loses the property sense). No model chooses anything: every
candidate keeps its WordNet rank, and a check reports the best and the first-ranked sense.

A check is `entailed` (the class is a subclass of what the relation requires), `not-entailed`
(compatible but not derivable), `disjoint` (SUMO's partitions rule it out), or `untyped`.
Findings name what SUMO could not represent; they are taxonomy observations, not legal ones.
"""
from __future__ import annotations

import re
from collections import Counter, defaultdict

PRONOUNS = {'i': 'I', 'me': 'I', 'my': 'I', 'mine': 'I', 'myself': 'I', 'you': 'you', 'your': 'you',
            'yours': 'you', 'yourself': 'you', 'we': 'we', 'us': 'we', 'our': 'we', 'ours': 'we',
            'ourselves': 'we'}
NER_CLASS = {'ORG': 'Organization', 'PERSON': 'Human', 'GPE': 'GeopoliticalArea', 'NORP': 'GroupOfPeople',
             'LAW': 'Proposition', 'MONEY': 'CurrencyMeasure'}
DEONTIC = {'obligation': 'holdsObligation', 'commitment': 'holdsObligation', 'prohibition': 'holdsObligation',
           'negative-commitment': 'holdsObligation', 'liability': 'holdsObligation', 'permission': 'holdsRight',
           'ability': 'holdsRight', 'no-obligation': 'holdsObligation', 'no-liability': 'holdsObligation',
           'no-prohibition': 'holdsRight'}
# Contract and statute register: the WordNet sense these heads carry in agreements, where the first sense is
# another register's (party.n.01 is a political party). Explicit and reviewable; each is WordNet's own gloss.
LEGAL_SENSES = {'party': 'party.n.05',            # a person involved in legal proceedings
                'parties': 'party.n.05',
                'interest': 'interest.n.05',      # (law) a right or legal share of something
                'balance': 'balance.n.06',        # the difference between the credit and debit sides of an account
                'instrument': 'legal_document.n.01',
                'account': 'account.n.03',        # a formal contractual relationship ... to provide financial services
                'security': 'security.n.04',      # a formal declaration that documents a fact of relevance to finance
                'holder': 'holder.n.03',          # the person in possession of a check or note or bond
                'thing': 'thing.n.08',            # an entity that is not named specifically (the object of ownership)
                'things': 'thing.n.08'}
ASSET_LEMMAS = {'asset', 'assets', 'fund', 'funds', 'money', 'currency', 'balance', 'token', 'stablecoin', 'usdc',
                'eth', 'bitcoin', 'cryptocurrency', 'deposit', 'cash', 'property', 'security', 'securities',
                'holding', 'holdings', 'reward', 'rewards', 'collateral', 'principal', 'ccs', 'lst', 'cbeth'}
REVOCABLE = {'consent', 'authorization', 'authorisation', 'application', 'request', 'offer', 'instruction',
             'complaint', 'election', 'designation', 'notice', 'order'}
CORPORATE = re.compile(r'\b(Inc|Incorporated|Corp|Corporation|Company|Co|LLC|L\.L\.C|Ltd|N\.A|National Association|PLC|LP|LLP)\b\.?')
ACRONYM = re.compile(r'^[A-Z][A-Z0-9]{1,6}s?$')
# Relations an overlay may add for bearers SUMO's own relations exclude; used only when defined.
ALTERNATIVES = {'holdsObligation': ['legalObligation'], 'holdsRight': ['legalRight'],
                'confersRight': ['confersLegalRight'], 'confersObligation': ['confersLegalObligation'],
                'possesses': ['owns']}
TRANSFER = {'transfer', 'deliver', 'send', 'return', 'withdraw', 'deposit', 'lend', 'loan', 'pledge', 'sell',
            'purchase', 'buy', 'redeem', 'debit', 'credit', 'convert', 'liquidate', 'stake', 'rehypothecate',
            'gain', 'acquire', 'receive', 'obtain', 'grant', 'assign', 'convey'}
CUSTODY = {'hold', 'keep', 'retain', 'store', 'safeguard', 'custody', 'detain', 'control', 'freeze', 'seize'}
DATA = {'disclose', 'share', 'collect', 'process', 'order'}
OWN = {'own', 'possess', 'belong'}
EXPECTED = {**{v: ['ChangeOfPossession', 'FinancialTransaction', 'Transfer', 'Giving', 'Getting', 'Translocation'] for v in TRANSFER},
            **{v: ['Keeping'] for v in CUSTODY},
            **{v: ['Disseminating', 'Communication', 'Stating', 'Getting', 'Requesting', 'Ordering'] for v in DATA},
            'sign': ['SigningAnAgreement', 'Writing']}
ORDER = {'entailed': 0, 'not-entailed': 1, 'untyped': 2, 'disjoint': 3}
CUSTODY_NOUNS = {'custody', 'custodian', 'custodial'}
STOP_MODIFIERS = {'other', 'additional', 'such', 'full', 'certain', 'any', 'all', 'applicable', 'relevant',
                  'particular', 'specific', 'new', 'sole', 'reasonable', 'prior', 'current', 'own', 'same', 'various',
                  'available', 'following', 'above', 'below', 'respective', 'potential', 'actual', 'certain'}
RIGHT_HEADS = {'right', 'claim', 'title', 'interest', 'entitlement'}
TRUST_NOUNS = {'trustee', 'trust', 'fiduciary', 'beneficiary'}
GENERIC_PERSON = {'he', 'she', 'him', 'her', 'himself', 'herself', 'someone', 'anyone', 'whoever', 'person'}
QUANTIFIERS = {'all', 'any', 'each', 'every', 'whatever', 'anything', 'everything', 'something', 'some', 'none',
               'either', 'neither', 'both', 'this', 'that', 'these', 'those', 'it', 'its', 'they', 'them', 'their',
               'which', 'what', 'who', 'whom', 'such', 'same', 'other', 'others'}
CONSTITUTIVE = {'constitute', 'be', 'have', 'remain', 'count', 'serve', 'operate', 'apply', 'govern', 'become',
                'survive', 'prevail', 'supersede', 'bind', 'transfer', 'vest', 'pass', 'continue', 'take', 'mean',
                'include', 'control', 'terminate', 'expire'}


def corporate_class(text: str):
    """What the text itself says about an organization's legal form: a chartered bank, or a
    corporate suffix. Silence leaves it an Organization."""
    if re.search(r'\b(chartered bank|national bank|National Association|N\.A\.|Bank)\b', text):
        return 'BankFinancialOrganization'
    if CORPORATE.search(text):
        return 'Corporation'
    return None


class Definitions:
    """Corpus-wide definitions: “X” means Y, and indefinite copular statements (“A thing in action
    is a right to recover money”), so one source's definition types another source's use."""

    def __init__(self):
        self.terms = {}

    def add(self, source: dict, frames: list[dict]):
        source_id = source['id']
        subject_dets = {'a', 'an', 'the'} if source.get('kind') == 'statute' else {'a', 'an'}
        for f in frames:
            cls = Context._class_of_antecedent(f) if f['rule'] == 'alias' else None
            party = f['rule'] == 'alias' and (any(a.lower() in PRONOUNS for a in f['aliases']) or (cls and cls != 'Organization'))
            if f['rule'] == 'alias' and not party:
                # “Coinbase Custom Stablecoins (“CCS”)”: a short name for a term, not a party
                for a in f['aliases']:
                    if a.lower() not in PRONOUNS:
                        self._put(norm_term(a), {'term': a, 'genus': [f['headNode']], 'source': source_id,
                                                 'quote': f['quote'], 'rule': 'alias'})
            if f['rule'] == 'definition':
                self._put(norm_term(f['term']), {'term': f['term'], 'genus': f['genus'], 'source': source_id,
                                                 'quote': f['quote'], 'rule': 'definition'})
            elif (f['rule'] == 'copular' and not f['negated'] and not f.get('modal') and f['predicate']['upos'] == 'NOUN'
                  and set(f['subject'].get('det', [])) & subject_dets and set(f['predicate'].get('det', [])) & {'a', 'an', 'the'}):
                key = ' '.join(f['subject']['compound'] + f['subject'].get('tail', []))
                self._put(key, {'term': key, 'genus': [f['predicate']], 'source': source_id, 'quote': f['quote'],
                                'rule': 'definitional-copular'})

    def _put(self, key, value):
        if key:
            self.terms.setdefault(key, value)
            if key.endswith('s'):
                self.terms.setdefault(key[:-1], value)

    def lookup(self, node):
        comp = node['compound']
        keys = []
        if node.get('tail'):
            keys.append(' '.join(comp + node['tail']))
        keys += [' '.join(comp[k:]) for k in range(len(comp))]
        for k in keys:
            if k in self.terms:
                return k, self.terms[k]
        return None, None


class Context:
    """Document-scoped facts the rules read: who “we”, “you” and “I” are, names, defined terms."""

    def __init__(self, frames: list[dict], definitions: Definitions | None = None):
        self.parties, self.names = {}, {}
        self.definitions = definitions or Definitions()
        self.defined = self.definitions.terms
        for f in frames:
            if f['rule'] == 'alias':
                cls = self._class_of_antecedent(f)
                party = any(a.lower() in PRONOUNS for a in f['aliases']) or (cls and cls != 'Organization')
                if not cls or not party:
                    continue
                for a in f['aliases']:
                    p = PRONOUNS.get(a.lower())
                    if p:
                        self.parties.setdefault(p, {'name': f['term'], 'class': cls, 'quote': f['quote'], 'via': 'alias'})
                    else:
                        self.names.setdefault(a.lower(), {'name': f['term'], 'class': cls, 'via': 'alias'})
                self.names.setdefault(f['term'].lower(), {'name': f['term'], 'class': cls, 'via': 'alias'})
            if f['rule'] == 'definition':
                words = re.findall(r'[A-Za-z]+', f['term'].lower())
                pronouns = {PRONOUNS[w] for w in words if w in PRONOUNS}
                ner = f['definiens'].get('ner')
                if pronouns and ner in NER_CLASS:
                    cls = NER_CLASS[ner]
                    if cls == 'Organization':
                        cls = corporate_class(f['definiens']['quote']) or cls
                    for p in pronouns:
                        self.parties.setdefault(p, {'name': f['definiens']['quote'], 'class': cls,
                                                    'quote': f['quote'], 'via': 'definition'})
                    self.names.setdefault(f['definiens']['text'].lower(), {'name': f['definiens']['quote'], 'class': cls,
                                                                           'via': 'definition'})

    @staticmethod
    def _class_of_antecedent(f):
        node = f['headNode']
        if node['lemma'] in PRONOUNS or node['text'].lower() in PRONOUNS:
            return 'Human' if PRONOUNS.get(node['text'].lower()) in ('you', 'I') else None
        cls = NER_CLASS.get(node.get('ner') or '') if node.get('ner') in ('ORG', 'PERSON', 'GPE', 'NORP') else None
        if cls == 'Organization':
            return corporate_class(f['term']) or cls
        return cls


def norm_term(t: str) -> str:
    t = re.sub(r'[“”"’‘\']', '', t).strip().lower()
    return re.sub(r'\s+', ' ', t)


class Typer:
    def __init__(self, sumo, lexicon):
        self.sumo, self.lex = sumo, lexicon
        self.cache = {}

    @staticmethod
    def cleaned(node):
        lem = node['compound'][:-1]
        sur = (node.get('surface') or node['compound'])[:-1]
        keep = [i for i, c in enumerate(lem) if c not in STOP_MODIFIERS]
        return [sur[i] for i in keep], [lem[i] for i in keep]

    def phrases(self, node):
        """Multiword phrases for SUMO's own English term formats, longest first (“bank account”). Single
        words stay with WordNet's sense-ranked mapping."""
        sur, lem = self.cleaned(node)
        heads = [node['text'].lower(), node['lemma']]
        tail = node.get('tail') or []
        out = []
        for extra in ([tail] if tail else []) + [[]]:
            for k in range(len(sur) + 1):
                for mods in (sur[k:], lem[k:]):
                    for h in heads:
                        p = ' '.join(mods + [h] + extra)
                        if p not in out:
                            out.append(p)
        return [p for p in out if ' ' in p]

    def variants(self, node, with_tail=False):
        sur, lem = self.cleaned(node)
        heads = [node['text'].lower(), node['lemma']] if node['text'].lower() != node['lemma'] else [node['lemma']]
        out = []
        for k in range(len(sur) + 1):
            for mods in (sur[k:], lem[k:]):
                for h in heads:
                    v = '_'.join(mods + [h] + (node.get('tail') or [] if with_tail else []))
                    if v not in out:
                        out.append(v)
        return out

    def type(self, node, ctx: Context, pos='n', depth=0):
        if node is None:
            return {'via': 'missing', 'classes': []}
        low = node['text'].lower()
        if low in PRONOUNS:
            p = PRONOUNS[low]
            if p in ctx.parties:
                party = ctx.parties[p]
                return {'via': f'party:{p}={party["name"]} ({party["via"]})', 'classes': [{'term': party['class'], 'rank': 0}]}
            if p in ('I', 'you'):
                return {'via': f'pronoun:{p} (person by default)', 'classes': [{'term': 'Human', 'rank': 0}]}
            return {'via': f'pronoun:{p} (unresolved)', 'classes': []}
        if low in GENERIC_PERSON or (low == 'one' and node['upos'] in ('NUM', 'PRON')):
            return {'via': f'generic-person:{low}', 'classes': [{'term': 'Human', 'rank': 0}]}
        if node['upos'] in ('PRON', 'DET') or low in QUANTIFIERS:
            return {'via': f'pronoun-or-quantifier:{low} (unresolved)', 'classes': []}
        for key in (norm_term(node['quote']), low):
            if key in ctx.names:
                n = ctx.names[key]
                return {'via': f'name:{n["name"]} ({n["via"]})', 'classes': [{'term': n['class'], 'rank': 0}]}
        key0, d0 = ctx.definitions.lookup(node)
        if d0 and depth < 3 and node.get('ner') and len(node['compound']) > 1:
            typed = [self.type(g, ctx, pos, depth + 1) for g in d0['genus'] if ' '.join(g['compound']) != key0]
            classes = [c for t in typed for c in t['classes']]
            if classes:
                return {'via': f'defined:“{d0["term"]}” ({d0["source"]}, {d0["rule"]}) → ' + ' / '.join(t['via'] for t in typed),
                        'classes': classes, 'selected': next((t['selected'] for t in typed if t.get('selected')), None),
                        'misses': [], 'defined': True}
        if node.get('ner') in ('ORG', 'PERSON', 'GPE', 'NORP') and node['upos'] == 'PROPN':
            cls = NER_CLASS[node['ner']]
            if cls == 'Organization':
                cls = corporate_class(node['quote']) or cls
            return {'via': f'ner:{node["ner"]}', 'classes': [{'term': cls, 'rank': 0}]}
        if ACRONYM.match(node['text']) and len(node['text']) <= 5 and not self.lex.is_lemma(node['text'].lower()):
            for phrase in self.phrases(node):
                terms = {t for t in self.sumo.term_format.get(phrase, ()) if t[:1].isupper()}
                if terms:
                    classes = [{'term': t, 'rank': 0, 'synset': None, 'relation': 'termFormat', 'gloss': phrase} for t in sorted(terms)]
                    return {'via': f'sumo-termFormat:“{phrase}”', 'classes': classes,
                            'selected': {**classes[0], 'selectedBy': 'termFormat'}, 'misses': []}
            key, d = ctx.definitions.lookup({**node, 'compound': [node['text'].lower()]})
            if d and depth < 3:
                typed = [self.type(g, ctx, pos, depth + 1) for g in d['genus']]
                classes = [c for t in typed for c in t['classes']]
                return {'via': f'defined:“{d["term"]}” ({d["source"]}, {d["rule"]}) → ' + ' / '.join(t['via'] for t in typed),
                        'classes': classes, 'selected': next((t['selected'] for t in typed if t.get('selected')), None),
                        'misses': [], 'defined': True}
            return {'via': 'acronym (no lexical lookup)', 'classes': [], 'misses': [node['text']], 'defined': False}
        for phrase in self.phrases(node):
            terms = {t for t in self.sumo.term_format.get(phrase, ()) if t[:1].isupper()}
            if terms:
                classes = [{'term': t, 'rank': 0, 'synset': None, 'relation': 'termFormat', 'gloss': phrase} for t in sorted(terms)]
                return self.role_holder({'via': f'sumo-termFormat:“{phrase}”', 'classes': classes,
                                         'selected': {**classes[0], 'selectedBy': 'termFormat'}, 'misses': []})
        misses = []
        tail_misses = []
        if node.get('tail'):
            for v in self.variants(node, with_tail=True):
                hit = self._wordnet(v, pos)
                if hit:
                    return self.role_holder({**hit, 'misses': misses, 'selected': self.select(node, hit['classes'])})
                tail_misses.append(v)
        key, d = ctx.definitions.lookup(node)
        if d and depth < 3:
            genus = [g for g in d['genus'] if ' '.join(g['compound']) != key]
            typed = [self.type(g, ctx, pos, depth + 1) for g in genus]
            classes = [c for t in typed for c in t['classes']]
            if classes:
                return {'via': f'defined:“{d["term"]}” ({d["source"]}, {d["rule"]}) → ' + ' / '.join(t['via'] for t in typed),
                        'classes': classes, 'selected': next((t['selected'] for t in typed if t.get('selected')), None),
                        'misses': misses, 'tailMisses': tail_misses, 'defined': True}
        for v in self.variants(node):
            hit = self._wordnet(v, pos)
            if hit:
                return self.role_holder({**hit, 'misses': misses, 'tailMisses': tail_misses, 'defined': bool(d),
                                         'selected': self.select(node, hit['classes'])})
            misses.append(v)
        return {'via': 'no-wordnet-entry', 'classes': [], 'misses': misses, 'tailMisses': tail_misses, 'defined': bool(d)}

    def select(self, node, classes):
        """Deterministic sense choice: a legal-right head with a complement (“a right to recover”,
        “title to Supported Digital Assets”) takes its first sense under NormativeAttribute; a head in
        LEGAL_SENSES takes that synset; otherwise WordNet's first-ranked mapped sense."""
        if not classes:
            return None
        if node['lemma'] in RIGHT_HEADS and (node.get('tail') or node.get('acl')):
            for c in classes:
                if c['term'] in self.sumo.known and self.sumo.isa(c['term'], 'NormativeAttribute'):
                    return {**c, 'selectedBy': 'legal-right-construction'}
        want = LEGAL_SENSES.get(node['text'].lower()) or LEGAL_SENSES.get(node['lemma'])
        if want:
            for c in classes:
                if c.get('synset') == want:
                    return {**c, 'selectedBy': f'legal-register:{want}'}
        return {**classes[0], 'selectedBy': 'first-mapped-sense'}

    def role_holder(self, typing):
        """SUMO models roles as Attributes (SocialRole, Position): the referent of “user”, “custodian”
        or “party” is the agent holding the role, typed Human unless a party alias says otherwise."""
        sel = typing.get('selected')
        if sel and sel['term'] in self.sumo.known and (self.sumo.isa(sel['term'], 'SocialRole') or self.sumo.isa(sel['term'], 'Position')):
            role = {**sel}
            return {**typing, 'via': typing['via'] + f' → role {sel["term"]} held by a Human', 'role': role,
                    'classes': [{'term': 'Human', 'rank': 0, 'synset': sel.get('synset'), 'gloss': sel.get('gloss')}],
                    'selected': {'term': 'Human', 'rank': 0, 'synset': sel.get('synset'), 'selectedBy': 'role-noun'}}
        return typing

    def _wordnet(self, v, pos):
        senses = self.lex.senses(v, pos)
        if not senses:
            return None
        classes = [{'term': m['term'], 'rank': s['rank'], 'synset': s['synset'], 'relation': m['relation'],
                    'gloss': s['gloss']} for s in senses for m in s['mappings'] if not m['relation'].startswith('negated')]
        return {'via': f'wordnet:{v}', 'classes': classes}

    def check(self, typing, required: str):
        rows = []
        for c in typing['classes'][:12]:
            t = c['term']
            if t not in self.sumo.known:
                status, witness = 'untyped', None
            elif self.sumo.isa(t, required):
                status, witness = 'entailed', None
            else:
                witness = self.sumo.disjoint_witness(t, required)
                status = 'disjoint' if witness else 'not-entailed'
            row = {**c, 'status': status, 'witness': witness}
            inc = self.sumo.incoherent(t) if t in self.sumo.known else None
            if inc:
                row['incoherent'] = inc
            rows.append(row)
        if not rows:
            return {'required': required, 'status': 'untyped', 'best': None, 'first': None, 'candidates': []}
        sel = typing.get('selected') or typing['classes'][0]
        chosen = next((r for r in rows if r['term'] == sel['term'] and r['rank'] == sel['rank']), rows[0])
        chosen = {**chosen, 'selectedBy': sel.get('selectedBy', 'only-candidate')}
        others = [f"{r['synset'] if r.get('synset') else r['term']}→{r['term']}" for r in rows
                  if r['status'] == 'entailed' and r is not chosen and r['term'] != chosen['term']]
        return {'required': required, 'status': chosen['status'], 'best': chosen, 'first': rows[0],
                'otherSensesEntailed': others[:4], 'candidates': rows[:6]}

    def check_relation(self, typing, relation: str, arg: int):
        """Check against the relation's domain, then any overlay alternative that admits more."""
        results = []
        for rel in [relation] + [a for a in ALTERNATIVES.get(relation, []) if a in self.sumo.known]:
            for req in sorted(self.sumo.domain.get((rel, arg), {'Entity'})):
                r = self.check(typing, req)
                r['relation'] = rel; r['arg'] = arg
                r['axiom'] = self.sumo.where.get(('domain', rel, str(arg), req))
                results.append(r)
        return min(results, key=lambda r: ORDER[r['status']]), results

    def process_senses(self, lemma: str, expected: list[str]):
        senses = self.lex.senses(lemma, 'v')
        rows = []
        for s in senses:
            for m in s['mappings']:
                if m['relation'].startswith('negated'):
                    continue
                t = m['term']
                hit = next((e for e in expected if t in self.sumo.known and self.sumo.isa(t, e)), None)
                rows.append({'rank': s['rank'], 'synset': s['synset'], 'term': t, 'gloss': s['gloss'],
                             'isProcess': t in self.sumo.known and self.sumo.isa(t, 'Process'), 'matches': hit})
        match = next((r for r in rows if r['matches']), None)
        return {'lemma': lemma, 'expected': expected, 'senses': len(senses), 'first': rows[0] if rows else None,
                'match': match, 'status': 'sense-match' if match else ('no-verb-entry' if not rows else 'sense-gap'),
                'candidates': rows[:6]}


def agentive(typer, typing):
    """Judged on the sense the typing selected, not on any sense: “the name” has a FamilyGroup sense."""
    sel = typing.get('selected') or (typing['classes'][0] if typing['classes'] else None)
    return bool(sel) and sel['term'] in typer.sumo.known and typer.sumo.isa(sel['term'], 'AutonomousAgent')


def is_party(typer, typing):
    """A party to the instrument: its own party aliases and pronouns, or a person."""
    if typing['via'].startswith(('party:', 'pronoun:', 'name:', 'generic-person:')):
        return True
    sel = typing.get('selected')
    return bool(sel) and sel['term'] in typer.sumo.known and typer.sumo.isa(sel['term'], 'Human')


def _findings_for_check(kind_prefix, role, node, chk, findings):
    if chk['status'] == 'untyped':
        return
    if chk['status'] in ('entailed',):
        return
    best = chk['best']
    term = best['term'] if best else None
    findings.append({'kind': f'{kind_prefix}', 'key': f"{chk['relation']}#{chk['arg']}:{term or 'untyped'}",
                     'role': role, 'node': node, 'status': chk['status'], 'required': chk['required'],
                     'term': term, 'axiom': chk.get('axiom'), 'witness': best.get('witness') if best else None})


def infer_frame(f: dict, typer: Typer, ctx: Context) -> dict:
    out = {'rule': f['rule'], 'checks': [], 'findings': [], 'formula': None}
    findings = out['findings']

    seen = {}

    def typed(node, pos='n'):
        if node is not None and node['id'] in seen:
            return seen[node['id']]
        t = typer.type(node, ctx, pos)
        if node is None:
            return t
        seen[node['id']] = t
        sel = t.get('selected')
        inc = typer.sumo.incoherent(sel['term']) if sel and sel['term'] in typer.sumo.known else None
        if inc:
            findings.append({'kind': 'incoherent-class', 'key': sel['term'], 'node': node, 'detail': inc,
                             'via': t['via']})
        ups = node.get('compoundUpos') or []
        named = 'PROPN' in ups or node.get('ner') in NER_CLASS or node['upos'] == 'ADJ'
        sur, _ = Typer.cleaned(node)
        whole = '_'.join(sur + [node['lemma']])
        missing = set(t.get('misses', []))
        key = whole if (whole in missing and sur) else (t['misses'][0] if t.get('misses') and t['via'] in (
            'no-wordnet-entry', 'acronym (no lexical lookup)') else None)
        if key and t['via'].startswith('acronym'):
            key = f'acronym:{node["text"]}'
        if key and not named:
            findings.append({'kind': 'lexical-gap', 'key': key, 'node': node, 'fallback': t['via'],
                             'defined': t.get('defined', False),
                             'fallbackClasses': [c['term'] for c in t['classes'][:3]]})
        return t

    def deontic(reading, bearers, action_lemma):
        rel = DEONTIC.get(reading)
        if not rel:
            return
        if not bearers:
            findings.append({'kind': 'bearer-unexpressed', 'key': reading, 'detail': 'agentless passive or missing subject'})
            return
        for b in bearers:
            t = typed(b)
            best, allr = typer.check_relation(t, rel, 1)
            out['checks'].append({'role': 'bearer', 'node': b, 'typing': t, 'check': best, 'all': allr})
            _findings_for_check('bearer-type', 'bearer', b, best, findings)
        if action_lemma:
            ps = typer.process_senses(action_lemma, ['Process'])
            out['checks'].append({'role': 'action', 'lemma': action_lemma, 'process': ps})
        # SUMO's argument order at the pinned ref: (holdsObligation AGENT FORMULA), (holdsRight AGENT FORMULA)
        act = f"<{action_lemma or 'act'}>"
        inner = f"(not {act})" if reading in ('prohibition', 'negative-commitment') else act
        core = f"({rel} <{', '.join(b['quote'] for b in bearers)}> {inner})"
        out['formula'] = f"(not {core})" if reading in ('no-obligation', 'no-liability') else core
        out['reading'] = reading

    def possession(holders, themes, source_rel='possesses'):
        for h in holders:
            t = typed(h)
            best, allr = typer.check_relation(t, source_rel, 1)
            out['checks'].append({'role': 'holder', 'node': h, 'typing': t, 'check': best, 'all': allr})
            _findings_for_check('holder-type', 'holder', h, best, findings)
        for th in themes:
            t = typed(th)
            best, allr = typer.check_relation(t, source_rel, 2)
            out['checks'].append({'role': 'possessed', 'node': th, 'typing': t, 'check': best, 'all': allr})
            _findings_for_check('possessed-type', 'possessed', th, best, findings)
        out['formula'] = f"(possesses <{', '.join(h['quote'] for h in holders) or '?'}> <{', '.join(t['quote'] for t in themes) or '?'}>)"

    rule = f['rule']
    if rule == 'modal-action' and not f.get('copular'):
        subjects = f['subjects'] if not f['passive'] else [o['node'] for o in f['obliques'] if o['case'] == 'by']
        readings = f['readings']
        r0 = readings[0]
        st = typer.type(subjects[0], ctx) if subjects else None
        is_agent = bool(subjects) and agentive(typer, st)
        if f['passive'] and not subjects:
            reading, why = r0, 'agentless-passive'
        elif is_agent and r0 in ('commitment', 'negative-commitment') and not is_party(typer, st) and len(readings) > 1:
            # “a court with jurisdiction will pick the administrator”: an instrument binds its parties; of
            # anyone else its “will” describes what happens
            reading, why = readings[1], 'non-party-subject'
        elif is_agent:
            reading, why = r0, 'agentive-subject'
        elif r0 in ('obligation', 'prohibition', 'commitment', 'negative-commitment') and f['headNode']['lemma'] in CONSTITUTIVE:
            reading, why = 'constitutive', 'non-agentive-subject'
        elif r0 in ('obligation', 'prohibition'):
            # “The name on your linked bank account ... must match the name verified”: a requirement on a state
            # of affairs, (modalAttribute FORMULA Obligation) in SUMO; no one is said to bear it
            reading, why = 'requirement', 'non-agentive-subject'
            out['formula'] = f"(modalAttribute ({f['headNode']['lemma']} <{subjects[0]['quote'] if subjects else '?'}> …) " \
                             f"{'Prohibition' if r0 == 'prohibition' else 'Obligation'})"
        elif len(readings) > 1:
            reading, why = readings[1], 'non-agentive-subject'
        else:
            reading, why = r0, 'single-reading'
        out['reading'], out['readingRule'] = reading, why
        if reading == 'constitutive' and 'countsAs' not in typer.sumo.known:
            findings.append({'kind': 'constitutive-rule', 'key': f"{f['modalLemma']} {f['headNode']['lemma']}",
                             'node': subjects[0] if subjects else f['headNode'],
                             'detail': 'a norm about a non-agent (legal effect or status), not an agent’s duty; SUMO has no '
                                       'counts-as relation, and confersNorm/modalAttribute attach a deontic attribute to a formula'})
        elif reading in DEONTIC:
            act = f['headNode']['lemma']
            if act in ('need', 'have', 'agree', 'continue', 'choose', 'elect', 'wish', 'want', 'intend', 'try', 'attempt') \
                    and f.get('complement'):
                act = f['complement']['node']['lemma']
            deontic(reading, subjects, act)
    elif rule == 'norm-predicate':
        subjects = f['subjects']
        action = (f.get('complement') or {}).get('node')
        passive = f['headNode']['xpos'] == 'VBN' or (f.get('complement') or {}).get('passive')
        if subjects and passive and not agentive(typer, typer.type(subjects[0], ctx)):
            # “A valid email address is required”, “does not need to be activated”: the subject is what the
            # norm is about; who must act is not said
            out['readingRule'] = 'non-agent-subject-of-passive-norm'
            subjects = []
        deontic(f['reading'], subjects, action['lemma'] if action else None)
    elif rule == 'grant':
        grantees = f['objects'][:1]
        if grantees and not f.get('complement') and not agentive(typer, typer.type(grantees[0], ctx)):
            out['authorizedAct'] = grantees[0]['quote']
            grantees = []
        for g in grantees:
            t = typed(g)
            best, allr = typer.check_relation(t, 'confersRight', 2)
            out['checks'].append({'role': 'grantee', 'node': g, 'typing': t, 'check': best, 'all': allr})
            _findings_for_check('grantee-type', 'grantee', g, best, findings)
        if not grantees:
            findings.append({'kind': 'grantee-unexpressed', 'key': f['verbLemma']})
        ps = typer.process_senses(f['verbLemma'], ['Process'])
        out['checks'].append({'role': 'act', 'lemma': f['verbLemma'], 'process': ps})
        if ps['status'] != 'sense-match' or (ps['first'] and not ps['first']['isProcess']):
            findings.append({'kind': 'act-without-process-class', 'key': f['verbLemma'], 'detail': ps['first'],
                             'status': ps['status'] if ps['status'] != 'sense-match' else 'first-sense-not-a-process'})
        out['formula'] = f"(confersRight <this text> <{', '.join(g['quote'] for g in grantees) or '?'}> <{(f.get('complement') or {}).get('full', '?')}>)"
    elif rule == 'assent':
        # “ANY ORDER WILL BE ... ACCEPTED”: a passive subject is what is accepted, released or waived
        parties = f['subjects'] if not f.get('passive') else [o['node'] for o in f['obliques'] if o['case'] == 'by']
        for s in parties:
            t = typed(s)
            best, allr = typer.check_relation(t, 'confersObligation', 2)
            out['checks'].append({'role': 'assenting-party', 'node': s, 'typing': t, 'check': best, 'all': allr})
            _findings_for_check('assenting-party-type', 'assenting-party', s, best, findings)
        if f.get('reading') in ('obligation', 'prohibition'):
            action = (f.get('complement') or {}).get('node')
            if action:
                ps = typer.process_senses(action['lemma'], ['Process'])
                out['checks'].append({'role': 'action', 'lemma': action['lemma'], 'process': ps})
    elif rule == 'property-event':
        lem = f['verbLemma']
        if lem in OWN:
            if lem == 'belong':
                holders = [o['node'] for o in f['obliques'] if o['case'] == 'to']
                possession(holders, f['subjects'])
            else:
                possession(f['agent'], f['theme'])
        else:
            exp = EXPECTED.get(lem)
            if exp:
                ps = typer.process_senses(lem, exp)
                out['checks'].append({'role': 'event', 'lemma': lem, 'process': ps})
                if ps['status'] != 'sense-match':
                    findings.append({'kind': 'event-sense-gap', 'key': f'{lem}→{"|".join(exp)}', 'detail': ps['first'],
                                     'status': ps['status']})
                elif ps['match']['rank'] > 1:
                    findings.append({'kind': 'event-first-sense-misfit', 'key': f'{lem}→{"|".join(exp)}',
                                     'detail': {'first': ps['first'], 'match': ps['match']}})
            asset_themes = [th for th in f['theme'] if set(th.get('compound', [])) & ASSET_LEMMAS or th['text'].lower() in ASSET_LEMMAS]
            if lem in CUSTODY and asset_themes and 'custodian' not in typer.sumo.known:
                findings.append({'kind': 'relation-gap', 'key': 'custody/bailment', 'node': asset_themes[0],
                                 'detail': f'SUMO has no relation for holding property owned by another ({lem})'})
            if lem in ('withdraw', 'revoke', 'cancel') and any(th['lemma'] in REVOCABLE for th in f['theme']):
                pass
            elif lem in TRANSFER | CUSTODY:
                for th in asset_themes:
                    t = typed(th)
                    best, allr = typer.check_relation(t, 'possesses', 2)
                    out['checks'].append({'role': 'theme-possessable', 'node': th, 'typing': t, 'check': best, 'all': allr})
                    _findings_for_check('possessed-type', 'theme', th, best, findings)
    elif rule == 'property-relation':
        lem = f['nounLemma']
        themes = [a['node'] for a in f['arguments'] if a['case'] in ('to', 'of', 'over', 'in')]
        holders = list(f['holders'])
        gov = f.get('governor') or {}
        holders += [o['node'] for o in gov.get('obliques', []) if o['case'] in ('with',)]
        if lem in ('title', 'ownership', 'owner'):
            possession(holders, themes)
            senses = typer.lex.senses(lem, 'n')
            out['checks'].append({'role': 'noun-senses', 'lemma': lem, 'senses': [
                {'rank': s['rank'], 'synset': s['synset'], 'terms': [m['term'] for m in s['mappings']], 'gloss': s['gloss']} for s in senses[:8]]})
        elif lem in CUSTODY_NOUNS:
            if 'custodian' not in typer.sumo.known:
                findings.append({'kind': 'relation-gap', 'key': 'custody/bailment', 'detail': f'noun “{f["noun"]}”'})
            senses = typer.lex.senses(lem, 'n')
            out['checks'].append({'role': 'noun-senses', 'lemma': lem, 'senses': [
                {'rank': s['rank'], 'synset': s['synset'], 'terms': [m['term'] for m in s['mappings']], 'gloss': s['gloss']} for s in senses[:8]]})
            findings.append({'kind': 'noun-sense-gap', 'key': lem,
                             'detail': [f"{s['synset']}→{','.join(m['term'] for m in s['mappings'])}" for s in senses]})
            for th in themes:
                t = typed(th)
                best, allr = typer.check_relation(t, 'possesses', 2)
                out['checks'].append({'role': 'in-custody', 'node': th, 'typing': t, 'check': best, 'all': allr})
                _findings_for_check('possessed-type', 'in-custody', th, best, findings)
        elif lem in TRUST_NOUNS and 'trustee' not in typer.sumo.known:
            findings.append({'kind': 'relation-gap', 'key': 'trust/trustee/beneficiary', 'detail': f'noun “{f["noun"]}”'})
    elif rule == 'copular':
        pred = f['predicate']
        subj = f['subject']
        ts = typed(subj)
        tp = typed(pred)
        out['checks'].append({'role': 'subject', 'node': subj, 'typing': ts})
        out['checks'].append({'role': 'predicate', 'node': pred, 'typing': tp})
        if pred['lemma'] in ('owner',):
            themes = [o['node'] for o in f['obliques'] if o['case'] == 'of']
            possession([subj], themes)
        if pred['lemma'] == 'property':
            holders = [o['node'] for o in f['obliques'] if o['case'] == 'of']
            if holders:
                possession(holders, [subj])
                out['negatedClaim'] = f['negated']
        if pred['upos'] == 'NOUN' and (pred['lemma'] in TRUST_NOUNS | CUSTODY_NOUNS or tp.get('role')):
            role_terms = [(tp.get('role') or {}).get('term')] + [c['term'] for c in tp['classes'][:2]]
            out['formula'] = f"(attribute <{subj['quote']}> <{pred['quote']}>)  ; roles are Attributes in SUMO: {role_terms}"
            chk = typer.check(ts, 'CognitiveAgent')
            if chk['status'] != 'entailed' and 'LegalPerson' in typer.sumo.known:
                alt = typer.check(ts, 'LegalPerson')
                if alt['status'] == 'entailed':
                    chk = alt
            out['checks'].append({'role': 'role-holder', 'node': subj, 'typing': ts, 'check': chk})
            if chk['status'] != 'entailed':
                findings.append({'kind': 'role-holder-type', 'key': f"{pred['lemma']}:{(chk['best'] or {}).get('term', 'untyped')}",
                                 'node': subj, 'status': chk['status'],
                                 'detail': 'SocialRole is documented as the position of a CognitiveAgent'})
            if pred['lemma'] in TRUST_NOUNS and 'trustee' not in typer.sumo.known:
                findings.append({'kind': 'relation-gap', 'key': 'trust/trustee/beneficiary', 'detail': f'“{pred["quote"]}”'})
        elif ts['classes'] and tp['classes'] and pred['upos'] in ('NOUN', 'PROPN') and not f['negated'] \
                and not f.get('predicateCase') and pred['lemma'] != 'property':
            a, b = (ts.get('selected') or ts['classes'][0])['term'], (tp.get('selected') or tp['classes'][0])['term']
            if a in typer.sumo.known and b in typer.sumo.known:
                w = typer.sumo.disjoint_witness(a, b)
                out['checks'].append({'role': 'instance-of', 'subject': a, 'predicate': b, 'disjoint': w})
                if w:
                    findings.append({'kind': 'status-disjoint', 'key': f'{a}⊥{b}', 'detail': w, 'node': pred})
            out['formula'] = f"(instance <{subj['quote']}> <{pred['quote']}>)"
    elif rule == 'treat-as':
        tt, ta = typed(f['theme']), typed(f['asClass'])
        out['checks'].append({'role': 'theme', 'node': f['theme'], 'typing': tt})
        out['checks'].append({'role': 'as', 'node': f['asClass'], 'typing': ta})
        out['formula'] = f"(instance <{f['theme']['quote']}> <{f['asClass']['quote']}>)"
    if rule not in ('definition', 'alias'):
        for n in role_nodes(f):
            typed(n)
    if rule == 'definition':
        node = {'text': f['term'].split()[-1] if f['term'].split() else f['term'], 'lemma': f['term'].split()[-1].lower() if f['term'].split() else '',
                'compound': [w.lower() for w in re.findall(r"[A-Za-z][A-Za-z\-]*", f['term'])], 'quote': f['term'], 'upos': 'NOUN', 'ner': None}
        whole = '_'.join(node['compound'])
        has = typer.lex.has_entry(whole) if whole else False
        genus = [typer.type(g, ctx) for g in f['genus']]
        out['checks'].append({'role': 'defined-term', 'term': f['term'], 'wordnet': whole if has else None,
                              'genus': [{'quote': g['quote'], 'via': t['via'], 'classes': [c['term'] for c in t['classes'][:3]]}
                                        for g, t in zip(f['genus'], genus)]})
        party = f['definiens'].get('ner') in NER_CLASS or f['term'].lower() in PRONOUNS or f['term'].lower() in QUANTIFIERS
        if not has and not party and not set(f['term'].lower().split()) <= QUANTIFIERS | set(PRONOUNS):
            findings.append({'kind': 'defined-term-gap', 'key': norm_term(f['term']),
                             'detail': {'genus': [(g['quote'], [c['term'] for c in t['classes'][:3]]) for g, t in zip(f['genus'], genus)]}})
    return out


def role_nodes(f):
    nodes = []
    for k in ('subjects', 'objects', 'agent', 'theme', 'holders'):
        nodes += f.get(k) or []
    for o in f.get('obliques') or []:
        nodes.append(o['node'])
    comp = f.get('complement') or {}
    nodes += comp.get('objects') or []
    for o in comp.get('obliques') or []:
        nodes.append(o['node'])
    for k in ('subject', 'predicate', 'theme', 'asClass'):
        if isinstance(f.get(k), dict):
            nodes.append(f[k])
    return [n for n in nodes if isinstance(n, dict) and n.get('upos') in ('NOUN', 'PROPN')]


def aggregate(results: list[tuple[dict, dict, dict]]):
    """results: (source, frame, inference). Group findings by (kind, key) with examples."""
    groups = defaultdict(lambda: {'count': 0, 'sources': Counter(), 'examples': [], 'details': []})
    for source, frame, inf in results:
        for fd in inf['findings']:
            g = groups[(fd['kind'], fd['key'])]
            g['count'] += 1
            g['sources'][source['id']] += 1
            ex = {'source': source['id'], 'section': frame.get('section'), 'rule': frame['rule'],
                  'quote': frame['quote'], 'u16Begin': frame['u16Begin'], 'u16End': frame['u16End'],
                  'span': (fd.get('node') or {}).get('quote')}
            if len(g['examples']) < 6 and all(e['quote'] != ex['quote'] for e in g['examples']):
                g['examples'].append(ex)
            d = {k: v for k, v in fd.items() if k not in ('node',)}
            if len(g['details']) < 2:
                g['details'].append(d)
    return groups
