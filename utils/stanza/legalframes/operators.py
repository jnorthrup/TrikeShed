"""Operator logic read off Stanza's dependency tree: which construction licenses which operator.

A sentence's logical skeleton (if / unless / because / causes / in order to / not / and / or, and the modal
or norm over a predicate) is rarely stated outright; the parse tree states it as constructions: a `mark`
under an `advcl`, a causal verb with its `obl`, a fixed preposition, an `aux` that is a modal. Each operator
here names the construction that produced it, the edges it read, its cue and operands as source quotes cut
by offset, and two weights.

deterministic weight
    base x resolved / required. `base` is a fixed weight for the construction, on three levels: LEXICAL
    (a causal verb is the clause's own predicate), EXPLICIT (the cue has one standing logical reading) and
    AMBIGUOUS (the cue has two: when, where, since, may, or). LEXICAL and EXPLICIT are the values TrikeShed's
    NlpcoreAxiomatics uses for a causal verb ROOT and an explicit if/provided conditional. `required` are
    the roles the construction names (cue, both predicates, both subjects, ...) and `resolved` those the
    tree fills explicitly rather than by inheritance, ellipsis or an anaphor. It is neither a parser
    confidence nor a probability.

ontological weight
    entailed / total over the SUMO checks the operator's own relation imposes on these operands: `causes`
    wants two Processes, `holdsObligation` a CognitiveAgent, a connective wants operands that have a SUMO
    class at all. Statuses are the typer's (entailed, not-entailed, untyped, disjoint). A disjoint check says
    SUMO's partitions exclude this operand from the relation's domain, so the operator needs another relation;
    it says nothing against the source. Sense choice is the deterministic first mapped sense; no model takes part.
    `anySense` is the same ratio when a failed check also counts if another mapped sense of the same word
    satisfies it (“transactions”: the record of proceedings first, the Transaction second). The pair brackets
    what sense choice alone can move: `value` is what the first sense gives, `anySense` what some sense could.

Nothing here admits a rule or establishes legal force. Operators are candidates with provenance.
"""
from __future__ import annotations

from collections import Counter, defaultdict

from .frames import NEGATORS, OBJECTS, Sentence

LEXICAL, EXPLICIT, AMBIGUOUS = 0.9, 0.8, 0.5
LEVELS = {LEXICAL: 'lexical', EXPLICIT: 'explicit', AMBIGUOUS: 'ambiguous'}
LEVEL_NOTES = {
    'lexical': 'a causal verb is the clause predicate (NlpcoreAxiomatics: 0.9 for a causal verb ROOT)',
    'explicit': 'the cue has one standing logical reading (NlpcoreAxiomatics: 0.8 for an explicit if/provided conditional)',
    'ambiguous': 'the cue has two standing readings; the construction does not choose between them',
}

# cue -> (operator class, base). A subordinator is read only as `mark` or `advmod` of a clause head.
SUBORDINATORS = {
    'if': ('condition', EXPLICIT), 'provided': ('condition', EXPLICIT), 'unless': ('exception', EXPLICIT),
    'except': ('exception', EXPLICIT), 'because': ('reason', EXPLICIT), 'although': ('concession', EXPLICIT),
    'though': ('concession', EXPLICIT), 'whereas': ('concession', EXPLICIT), 'before': ('temporal', EXPLICIT),
    'after': ('temporal', EXPLICIT), 'until': ('temporal', EXPLICIT),
    'when': ('temporal', AMBIGUOUS),      # temporal or conditional
    'whenever': ('temporal', AMBIGUOUS),
    'once': ('temporal', AMBIGUOUS),
    'where': ('condition', AMBIGUOUS),    # circumstance or condition
    'while': ('temporal', AMBIGUOUS),     # temporal or concessive
    'since': ('reason', AMBIGUOUS),       # causal or temporal
}
CLASS_OPERATOR = {'condition': 'IMPLIES', 'exception': 'UNLESS', 'reason': 'BECAUSE', 'concession': 'ALTHOUGH',
                  'purpose': 'IN-ORDER-TO', 'cause': 'CAUSES', 'negation': 'NOT'}
SUBORDINATE_ROLE = {'condition': 'antecedent', 'exception': 'exception', 'reason': 'reason',
                    'concession': 'concession', 'temporal': 'time'}
# What SUMO offers for the operator: a relation with domain axioms, or a KIF connective (None: nothing to look up).
CLASS_RELATION = {'condition': None, 'exception': None, 'reason': 'causesProposition', 'concession': None,
                  'purpose': 'hasPurpose', 'cause': 'causes', 'negation': None, 'conjunction': None,
                  'disjunction': None}
# Temporal cue -> (SUMO relation, form over the subordinate clause A and the main clause C).
TEMPORAL = {'before': ('earlier', '(earlier (WhenFn {c}) (WhenFn {a}))'),
            'after': ('earlier', '(earlier (WhenFn {a}) (WhenFn {c}))'),
            'until': ('meetsTemporally', '(meetsTemporally (WhenFn {c}) (WhenFn {a}))')}
TEMPORAL_DEFAULT = ('holdsDuring', '(holdsDuring (WhenFn {a}) {c})')
CLAUSE_RELATIONS = ('advcl', 'advcl:relcl', 'acl', 'acl:relcl')
# Causal predicates: lemma -> how the tree lays out cause and effect.
#   object:  cause = subject, effect = obj          passive or participle: cause = by-agent, effect = subject/noun
#   in / to: cause = subject, effect = obl(case)    from / out of: cause = obl(case), effect = subject/noun
CAUSAL_VERBS = {'cause': {'object'}, 'trigger': {'object'}, 'result': {'in', 'from'}, 'lead': {'to'},
                'arise': {'from', 'out of'}}
FORWARD_CASES = {'in', 'to'}
CAUSAL_CASES = {'because of': 'because', 'due to': 'due'}
COORDINATORS = {'and': 'AND', 'but': 'AND', 'or': 'OR', 'nor': 'OR', 'and/or': 'OR'}
PARTY_PRONOUNS = {'i', 'me', 'you', 'we', 'us', 'he', 'she', 'they', 'them', 'one'}
# Reading -> (SUMO relation, NormativeAttribute instance, where the reading's own negation sits). A reading SUMO
# has no relation for is absent and costs the operator its relation check.
MODAL_SUMO = {'obligation': ('holdsObligation', None, None), 'prohibition': ('holdsObligation', None, 'inner'),
              'commitment': ('holdsObligation', None, None), 'negative-commitment': ('holdsObligation', None, 'inner'),
              'liability': ('holdsObligation', None, None), 'no-obligation': ('holdsObligation', None, 'outer'),
              'no-liability': ('holdsObligation', None, 'outer'), 'permission': ('holdsRight', None, None),
              'ability': ('holdsRight', None, None), 'no-prohibition': ('holdsRight', None, None),
              'requirement': ('modalAttribute', 'Obligation', None),
              'possibility': ('modalAttribute', 'Possibility', None),
              'inability': ('modalAttribute', 'Possibility', 'outer'),
              'conditional': ('modalAttribute', 'Possibility', None),
              'prediction': ('modalAttribute', 'Likely', None)}
# Readings that already say “not”: the negator under the predicate is part of the reading, not a second operator.
NEGATIVE_READINGS = {'prohibition', 'negative-commitment', 'inability', 'discouragement', 'no-obligation',
                     'no-liability', 'no-prohibition', 'ineligibility'}


def modal_form(reading, relation, attribute, negation, who, act, modal=None) -> str:
    inner = f'(not {act})' if negation == 'inner' else act
    if relation in ('holdsObligation', 'holdsRight'):
        form = f'({relation} <{who}> {inner})'
    elif relation == 'modalAttribute':
        form = f'(modalAttribute {inner} {attribute})'
    else:   # no SUMO relation, or no reading selected: the modal itself, uninterpreted
        form = f'({reading or "modal:" + (modal or "?")} {inner})'
    return f'(not {form})' if negation == 'outer' else form
# Lexical classes of single-word cues, shared with the masked-language-model lane: each cue word is in one class.
CUE_CLASSES = {
    'condition': ['if', 'provided', 'where', 'whenever'],
    'exception': ['unless', 'except'],
    'temporal': ['when', 'once', 'before', 'after', 'until', 'while', 'upon'],
    'reason': ['because', 'since', 'as', 'due'],
    'concession': ['although', 'though', 'whereas'],
    'necessity': ['shall', 'must'],
    'possibility': ['may', 'can', 'might', 'could'],
    'future': ['will', 'would'],
    'advice': ['should'],
    'negation': ['not', 'never'],
    'conjunction': ['and'],
    'disjunction': ['or'],
    'causation': ['cause', 'causes', 'caused', 'result', 'results', 'resulted', 'resulting', 'lead', 'leads',
                  'led', 'trigger', 'triggers', 'triggered', 'arise', 'arises', 'arising'],
    'purpose': ['order', 'so', 'to'],
}
CUE_CLASS_OF = {w: c for c, ws in CUE_CLASSES.items() for w in ws}


def deterministic_weight(base: float, roles: dict) -> dict:
    required = list(roles)
    resolved = [r for r in required if roles[r]]
    return {'value': round(base * len(resolved) / len(required), 4), 'base': base, 'level': LEVELS[base],
            'resolved': len(resolved), 'required': len(required),
            'unresolvedRoles': [r for r in required if not roles[r]]}


def ontological_weight(checks: list) -> dict:
    checks = [c for c in checks if c]
    if not checks:
        return {'value': None, 'anySense': None, 'entailed': 0, 'otherSense': 0, 'total': 0, 'statuses': {},
                'disjoint': False}
    counts = Counter(c['status'] for c in checks)
    other = sum(1 for c in checks if c['status'] != 'entailed'
                and (c.get('otherSensesEntailed') or c.get('otherSenseIsProcess')))
    return {'value': round(counts['entailed'] / len(checks), 4),
            'anySense': round((counts['entailed'] + other) / len(checks), 4),
            'entailed': counts['entailed'], 'otherSense': other, 'total': len(checks),
            'statuses': dict(sorted(counts.items())), 'disjoint': counts['disjoint'] > 0}


class Extractor:
    """Operators of one sentence. `typer`/`ctx` (legalframes.infer) add the SUMO checks; without them the
    structure and the deterministic weight are still produced and the ontological weight is None.
    `frames` are this sentence's (frame, inference) pairs: modal and norm readings come from them."""

    def __init__(self, record: dict, text: str, typer=None, ctx=None, frames=(), u16=None):
        self.s = Sentence(record, text)
        self.record, self.text, self.typer, self.ctx, self.u16 = record, text, typer, ctx, u16
        self.frames = list(frames)
        self.ops: list[dict] = []
        self.at = defaultdict(lambda: {'modal': None, 'neg': None, 'causal': None, 'coord': None, 'subs': [], 'pps': []})
        self._formula = {}

    # -- tree reading ----------------------------------------------------------------------
    def predicate(self, i) -> bool:
        return self.s.w[i]['upos'] == 'VERB' or bool(self.s.ch(i, 'cop'))

    def referential(self, i) -> bool:
        """A noun, a name, a party pronoun or a clause: something the tree itself identifies. A demonstrative,
        an expletive or a bare relative pronoun needs an antecedent the sentence does not give."""
        w = self.s.w[i]
        if w['upos'] in ('NOUN', 'PROPN', 'VERB', 'NUM'):
            return True
        return w['upos'] == 'PRON' and self.s.low(i) in PARTY_PRONOUNS

    def subject(self, v):
        """(ids, explicit): explicit when the clause has its own referential subject."""
        ids, inherited = self.s.subjects(v)
        return ids, bool(ids) and not inherited and all(self.referential(i) for i in ids)

    def span(self, ids):
        b, e, q = self.s.quote(sorted(ids))
        out = {'quote': q, 'begin': b, 'end': e}
        if self.u16 is not None:
            out['u16Begin'], out['u16End'] = self.u16[b], self.u16[e]
        return out

    def clause_ids(self, v, first_conjunct=False, without=()):
        skip = ('punct', 'advcl', 'advcl:relcl', 'parataxis', 'cc') + (('conj',) if first_conjunct else ())
        gone = set(without)
        return [i for i in self.s.subtree(v, skip=skip) if i not in gone] or [v]

    def names(self, ids):
        """Quotes of nominal subjects; a clausal subject is an operand, not a bearer."""
        return [self.s.node(k)['quote'] for k in ids if not self.s.rel(k).startswith('csubj')]

    def clause(self, role, v, first_conjunct=False, without=()) -> dict:
        s = self.s
        ids, explicit = self.subject(v)
        modal = s.modal_of(v)
        return {'role': role, 'kind': 'clause', 'head': v, 'predicate': s.verb_lemma(v), 'upos': s.w[v]['upos'],
                'copular': bool(s.ch(v, 'cop')), 'passive': s.passive(v), 'negated': s.negated(v),
                'modal': s.w[modal[0]]['text'] if modal else None,
                'subjects': self.names(ids), 'subjectExplicit': explicit,
                'objects': [s.node(k)['quote'] for k in s.ch(v, *OBJECTS)],
                **self.span(self.clause_ids(v, first_conjunct, without))}

    def nominal(self, role, i) -> dict:
        node = self.s.node(i)
        return {'role': role, 'kind': 'nominal', 'head': i, 'lemma': node['lemma'], 'upos': node['upos'],
                'referential': self.referential(i), **self.span(self.s.subtree(i, skip=('punct', 'acl', 'acl:relcl', 'case')))}

    # -- SUMO checks -----------------------------------------------------------------------
    def relation_check(self, relation, attribute=None, note='SUMO has no relation for this reading'):
        """The relation (and the attribute instance a modal reading needs) must exist in the pinned corpus."""
        if self.typer is None:
            return []
        known = self.typer.sumo.known
        if relation is None:
            return [{'role': 'relation', 'required': None, 'status': 'untyped', 'note': note}]
        rows = [{'role': 'relation', 'required': relation, 'status': 'entailed' if relation in known or self._has_domain(relation) else 'untyped'}]
        if attribute:
            rows.append({'role': 'attribute', 'required': attribute, 'status': 'entailed' if attribute in known else 'untyped'})
        return rows

    def _has_domain(self, relation):
        return any(k[0] == relation for k in self.typer.sumo.domain)

    def formula_check(self, v, required_process=False):
        """A clause can be written as a SUMO formula when its predicate has a SUMO class: a Process for a verb,
        any class for a copular noun or adjective. `required_process`: the relation needs an event (causes)."""
        if self.typer is None:
            return None
        key = (v, required_process)
        if key in self._formula:
            return self._formula[key]
        s, t = self.s, self.typer
        w = s.w[v]
        if w['upos'] == 'VERB':   # also “the remedy is to terminate”: a verb under a copula is still the event
            lemma, ps = None, None
            for lemma in dict.fromkeys([s.verb_lemma(v), s.lem(v)]):
                ps = t.process_senses(lemma, ['Process'])
                if ps['first']:
                    break
            first = ps['first']
            status = 'untyped' if not first else ('entailed' if first['isProcess'] else 'not-entailed')
            out = {'role': 'formula', 'head': v, 'required': 'Process', 'lemma': lemma, 'status': status,
                   'term': first['term'] if first else None, 'synset': first['synset'] if first else None,
                   'via': 'wordnet verb, first mapped sense',
                   'otherSenseIsProcess': bool(ps['match']) and not (first and first['isProcess'])}
        else:
            required = 'Process' if required_process else 'Entity'
            typing = t.type(s.node(v), self.ctx, 'a' if w['upos'] == 'ADJ' else 'n')
            chk = t.check(typing, required)
            best = chk['best'] or {}
            out = {'role': 'formula', 'head': v, 'required': required, 'lemma': s.lem(v), 'status': chk['status'],
                   'term': best.get('term'), 'synset': best.get('synset'), 'via': typing['via'],
                   'witness': best.get('witness'), 'otherSensesEntailed': chk.get('otherSensesEntailed') or []}
        self._formula[key] = out
        return out

    def argument_check(self, role, i, relation, arg):
        if self.typer is None:
            return None
        typing = self.typer.type(self.s.node(i), self.ctx)
        best, _ = self.typer.check_relation(typing, relation, arg)
        chosen = best['best'] or {}
        return {'role': role, 'head': i, 'relation': relation, 'arg': arg, 'required': best['required'],
                'status': best['status'], 'term': chosen.get('term'), 'synset': chosen.get('synset'),
                'axiom': best.get('axiom'), 'via': typing['via'], 'witness': chosen.get('witness'),
                'otherSensesEntailed': best.get('otherSensesEntailed') or []}

    @staticmethod
    def frame_checks(inference):
        """Bearer checks the frame inference already made against holdsObligation / holdsRight."""
        rows = []
        for c in inference.get('checks', []):
            chk = c.get('check')
            if chk and c.get('role') == 'bearer':
                best = chk.get('best') or {}
                rows.append({'role': 'bearer', 'head': c['node']['id'], 'relation': chk.get('relation'),
                             'arg': chk.get('arg'), 'required': chk['required'], 'status': chk['status'],
                             'term': best.get('term'), 'synset': best.get('synset'), 'axiom': chk.get('axiom'),
                             'via': c['typing']['via'], 'witness': best.get('witness'),
                             'otherSensesEntailed': chk.get('otherSensesEntailed') or []})
        return rows

    # -- operators -------------------------------------------------------------------------
    def emit(self, cls, operator, construction, base, cue_ids, key, edges, operands, roles, relation, checks,
             form, articulation, unresolved=(), **extra):
        s = self.s
        op = {'sentence': self.record['index'], 'class': cls, 'operator': operator, 'construction': construction,
              'cue': {**self.span(cue_ids), 'ids': sorted(cue_ids), 'key': key, 'keyText': s.w[key]['text'],
                      'keyBegin': s.w[key]['begin'], 'keyEnd': s.w[key]['end'],
                      'lexicalClass': CUE_CLASS_OF.get(s.low(key))},
              'edges': edges, 'operands': operands,
              'sumo': {'relation': relation, 'form': form, 'checks': [c for c in checks if c]},
              'weights': {'deterministic': deterministic_weight(base, roles),
                          'ontological': ontological_weight(checks)},
              'unresolved': sorted(set(unresolved) | {'legal-force-not-established'}),
              'articulation': articulation, 'admitted': False, 'kind': 'candidate',
              'sentenceBegin': self.record['begin'], 'sentenceEnd': self.record['end'], **extra}
        self.ops.append(op)
        return op

    def subordinators(self):
        s = self.s
        for k in sorted(s.w):
            rel = s.rel(k)
            main = s.w[k]['head']
            if rel not in CLAUSE_RELATIONS or main not in s.w:
                continue
            for m in s.ch(k, 'mark', 'advmod'):
                cue = s.low(m)
                if cue not in SUBORDINATORS or (cue == 'provided' and s.w[m]['upos'] != 'SCONJ'):
                    continue
                cls, base = SUBORDINATORS[cue]
                operator = CLASS_OPERATOR.get(cls) or ('WHEN' if cue in ('whenever', 'once') else cue.upper())
                sub = self.clause(SUBORDINATE_ROLE[cls], k, without=[m])
                top = self.clause('consequent' if cls in ('condition', 'exception') else 'main', main)
                roles = {'cue': True, 'adverbial-attachment': rel.startswith('advcl'),
                         'subordinate-predicate': self.predicate(k), 'subordinate-subject': sub['subjectExplicit'],
                         'main-predicate': self.predicate(main), 'main-subject': top['subjectExplicit']}
                a, c = '<%s>' % sub['predicate'], '<%s>' % top['predicate']
                if cls == 'temporal':
                    relation, template = TEMPORAL.get(cue, TEMPORAL_DEFAULT)
                    form = template.format(a=a, c=c)
                else:
                    relation = CLASS_RELATION[cls]
                    form = {'condition': f'(=> {a} {c})', 'exception': f'(=> (not {a}) {c})',
                            'reason': f'(causesProposition {a} {c})', 'concession': f'(and {a} {c})'}[cls]
                checks = (self.relation_check(relation) if relation else []) + [self.formula_check(k), self.formula_check(main)]
                words = {'condition': 'IF [%s] THEN [%s]', 'exception': 'UNLESS [%s], [%s]', 'reason': 'BECAUSE [%s], [%s]',
                         'concession': 'ALTHOUGH [%s], [%s]'}.get(cls, operator + ' [%s], [%s]')
                unresolved = ['guard-scope-not-established']
                if base == AMBIGUOUS:
                    unresolved.append(f'cue-reading-not-established:{cue}')
                op = self.emit(cls, operator, f'mark({cue})+{rel}', base, [m], m, [s.edge(k), s.edge(m)], [sub, top],
                               roles, relation, checks, form, words % (sub['quote'], top['quote']), unresolved)
                self.at[main]['subs'].append((op, k))

    def purposes(self):
        s = self.s
        for k in sorted(s.w):
            main = s.w[k]['head']
            if s.rel(k) != 'advcl' or main not in s.w:
                continue
            marks = s.ch(k, 'mark')
            lows = [s.low(m) for m in marks]
            order = [m for m in marks if s.low(m) == 'in' and any(s.low(f) == 'order' for f in s.ch(m, 'fixed'))]
            so = [m for m in marks if s.low(m) == 'so' and ('that' in lows or any(s.low(f) == 'that' for f in s.ch(m, 'fixed')))]
            if order:
                fixed = [f for f in s.ch(order[0], 'fixed') if s.low(f) == 'order']
                cue_ids, key, base, name = [order[0]] + fixed + [m for m in marks if s.low(m) == 'to'], fixed[0], EXPLICIT, 'in order to'
            elif so:
                cue_ids, key, base, name = [so[0]] + [m for m in marks if s.low(m) == 'that'] + s.ch(so[0], 'fixed'), so[0], EXPLICIT, 'so that'
            elif lows == ['to'] and s.w[k]['xpos'] == 'VB':
                cue_ids, key, base, name = [marks[0]], marks[0], AMBIGUOUS, 'to'
            else:
                continue
            top, sub = self.clause('main', main), self.clause('purpose', k, without=cue_ids)
            roles = {'cue': True, 'main-predicate': self.predicate(main), 'purpose-predicate': self.predicate(k)}
            checks = self.relation_check('hasPurpose') + [self.formula_check(main), self.formula_check(k)]
            unresolved = ['guard-scope-not-established'] + (['cue-reading-not-established:to'] if base == AMBIGUOUS else [])
            op = self.emit('purpose', 'IN-ORDER-TO', f'mark({name})+advcl', base, cue_ids, key,
                           [s.edge(k)] + [s.edge(m) for m in cue_ids], [top, sub], roles, 'hasPurpose', checks,
                           '(hasPurpose <%s> <%s>)' % (top['predicate'], sub['predicate']),
                           '[%s] IN ORDER TO [%s]' % (top['quote'], sub['quote']), unresolved)
            self.at[main]['subs'].append((op, k))

    def _cased(self, v, cases):
        """Obliques of v whose case (with its fixed words) is one of `cases`."""
        return [(k, self.s.case_of(k)) for k in self.s.ch(v, 'obl', 'obl:agent', 'nmod') if self.s.case_of(k) in cases]

    def _causal_oblique(self, v, layouts):
        """(argument, case, attachment). `own`: the verb's cased oblique. `stranded`: coordination left the
        preposition alone under the verb (“resulting from or arising out of X”) and X follows as a bare oblique.
        `shared`: a later conjunct reads the first conjunct's oblique."""
        s = self.s
        hits = self._cased(v, layouts)
        if hits:
            return hits[0][0], hits[0][1], 'own'
        stranded = [k for k in s.kids.get(v, ()) if s.w[k]['upos'] == 'ADP' and s.low(k) in layouts]
        nominal = [k for k in s.ch(v, 'obl', 'nmod') if s.w[k]['upos'] in ('NOUN', 'PROPN')]
        if stranded and nominal:
            return nominal[0], s.low(stranded[0]), 'stranded'
        if s.rel(v) == 'conj' and s.w[v]['head'] in s.w:
            hits = self._cased(s.w[v]['head'], layouts)
            if hits:
                return hits[0][0], hits[0][1], 'shared'
        return None

    def _participle_noun(self, v):
        """The noun a subjectless participle modifies, through coordination: “losses resulting from or arising ...”."""
        s = self.s
        h = v
        while s.rel(h) == 'conj' and s.w[h]['head'] in s.w:
            h = s.w[h]['head']
        if s.rel(h) in ('acl', 'acl:relcl') and not s.ch(h, 'nsubj', 'nsubj:pass') and s.w[h]['head'] in s.w:
            return s.w[h]['head']
        return None

    def causal_predicates(self):
        s = self.s
        for v in sorted(s.w):
            layouts = CAUSAL_VERBS.get(s.lem(v))
            if not layouts or s.w[v]['upos'] != 'VERB':
                continue
            noun = self._participle_noun(v)
            subjects, _ = s.subjects(v)
            subjects = subjects or ([noun] if noun is not None else [])
            cause = effect = None
            attachment = 'own'
            if 'object' in layouts:
                agents = self._cased(v, {'by'})
                if s.passive(v) or (noun is not None and s.w[v]['xpos'] == 'VBN'):
                    cause, effect, how = (agents[0][0] if agents else None), (subjects[0] if subjects else None), 'passive+by'
                else:
                    objs = s.ch(v, 'obj')
                    cause, effect, how = (subjects[0] if subjects else None), (objs[0] if objs else None), 'obj'
            else:
                hit = self._causal_oblique(v, layouts)
                if not hit:
                    continue
                k, case, attachment = hit
                how = f'obl({case})'
                if case in FORWARD_CASES:
                    cause, effect = (subjects[0] if subjects else None), k
                else:
                    cause, effect = k, (subjects[0] if subjects else None)
            if cause is None and effect is None:
                continue
            edges = [s.edge(i) for i in (cause, effect) if i is not None and i != s.w[v]['head']]
            operands = [self.nominal(r, i) for r, i in (('cause', cause), ('effect', effect)) if i is not None]
            roles = {'predicate': True, 'cause': cause is not None and self.referential(cause),
                     'effect': effect is not None and self.referential(effect), 'argument-attachment': attachment == 'own'}
            checks = self.relation_check('causes') + [
                self.argument_check('cause', cause, 'causes', 1) if cause is not None else None,
                self.argument_check('effect', effect, 'causes', 2) if effect is not None else None]
            q = {o['role']: o['quote'] for o in operands}
            unresolved = [f'{r}-anaphor-or-missing' for r in ('cause', 'effect') if not roles[r]]
            if attachment != 'own':
                unresolved.append(f'argument-{attachment}-by-coordination')
            self.at[v]['causal'] = self.emit(
                'cause', 'CAUSES', f'{s.lem(v)}+{how}', LEXICAL, [v], v, edges, operands, roles, 'causes', checks,
                '(causes <%s> <%s>)' % (q.get('cause', '?'), q.get('effect', '?')),
                '[%s] CAUSES [%s]' % (q.get('cause', '?'), q.get('effect', '?')), unresolved, head=v)

    def cased_nominals(self):
        """Fixed prepositions that carry an operator: because of / due to X, as a result of X, in the event of X."""
        s = self.s
        for x in sorted(s.w):
            g = s.w[x]['head']
            if g not in s.w:
                continue
            case = s.case_of(x)
            cls = cue_ids = key = arg = name = None
            if case in CAUSAL_CASES and s.rel(x) in ('obl', 'nmod', 'obl:npmod'):
                c = s.ch(x, 'case', 'mark')[0]
                cls, cue_ids, key, arg, name = 'reason', [c] + s.ch(c, 'fixed'), c, x, case
            elif s.lem(x) == 'result' and s.w[x]['upos'] == 'NOUN' and case == 'as':
                of = [k for k in s.ch(x, 'nmod') if s.case_of(k) == 'of']
                if of:
                    cls, cue_ids, key, arg, name = 'reason', s.ch(x, 'case', 'det') + [x] + s.ch(of[0], 'case'), x, of[0], 'as a result of'
            elif s.lem(x) == 'event' and s.w[x]['upos'] == 'NOUN' and case == 'in':
                of = [k for k in s.ch(x, 'nmod') if s.case_of(k) == 'of']
                if of:
                    cls, cue_ids, key, arg, name = 'condition', s.ch(x, 'case', 'det') + [x] + s.ch(of[0], 'case'), x, of[0], 'in the event of'
            if cls is None:
                continue
            nominal = self.nominal('reason' if cls == 'reason' else 'antecedent', arg)
            top = self.clause('effect' if cls == 'reason' else 'consequent', g, without=s.subtree(x))
            roles = {'cue': True, 'nominal': self.referential(arg), 'main-predicate': self.predicate(g),
                     'main-subject': top['subjectExplicit']}
            if cls == 'reason':
                relation, operator = 'causes', 'CAUSES'
                checks = self.relation_check('causes') + [self.argument_check('cause', arg, 'causes', 1),
                                                          self.formula_check(g, required_process=True)]
                form = '(causes <%s> <%s>)' % (nominal['quote'], top['predicate'])
                words = '[%s] CAUSES [%s]' % (nominal['quote'], top['quote'])
            else:
                relation, operator = None, 'IMPLIES'
                typing = self.typer.type(s.node(arg), self.ctx) if self.typer else None
                chk = self.typer.check(typing, 'Process') if self.typer else None
                checks = [self.formula_check(g)] + ([{'role': 'antecedent', 'head': arg, 'required': 'Process',
                                                      'status': chk['status'], 'term': (chk['best'] or {}).get('term'),
                                                      'via': typing['via']}] if chk else [])
                form = '(=> <%s> <%s>)' % (nominal['quote'], top['predicate'])
                words = 'IF [%s] THEN [%s]' % (nominal['quote'], top['quote'])
            op = self.emit(cls, operator, f'case({name})+{s.rel(x)}', EXPLICIT, cue_ids, key,
                           [s.edge(x)] + [s.edge(i) for i in cue_ids if i != x], [nominal, top], roles, relation, checks,
                           form, words, ['guard-scope-not-established'])
            self.at[g]['pps'].append(op)

    def modals(self):
        s = self.s
        for f, inf in self.frames:
            if f['rule'] == 'modal-action':
                v, m = f['head'], f['edges'][0][1]
                reading = inf.get('reading')     # None: the frame inference leaves a modal over a copular predicate unread
                relation, attribute, negation = MODAL_SUMO.get(reading, (None, None, None))
                top = self.clause('action', v)
                bearer = bool(f['subjects']) and top['subjectExplicit'] if not f['passive'] else \
                    any(o['case'] == 'by' for o in f['obliques'])
                roles = {'modal': True, 'predicate': True, 'bearer': bearer, 'reading-selected': reading is not None}
                base = EXPLICIT if len(f['readings']) == 1 else AMBIGUOUS
                note = 'SUMO has no relation for this reading' if reading else 'no reading selected: modal over a copular predicate'
                checks = self.relation_check(relation, attribute, note) + self.frame_checks(inf) + [self.formula_check(v)]
                consumed = bool(f['negated']) and reading in NEGATIVE_READINGS
                if reading in NEGATIVE_READINGS and not negation:
                    negation = 'inner'       # “should not”: no SUMO relation, the reading still carries the negation
                form = modal_form(reading, relation, attribute, negation, ', '.join(top['subjects']) or '?',
                                  '<%s>' % top['predicate'], s.low(m))
                unresolved = [u for u in f['unresolved'] if u.startswith(('coordination', 'subject-inherited'))]
                if reading is None:
                    unresolved.append('modal-reading-not-selected')
                elif base == AMBIGUOUS:
                    unresolved.append('modal-reading-by-rule:' + (inf.get('readingRule') or 'unselected'))
                name = reading.upper() if reading else 'MODAL'
                self.at[v]['modal'] = self.emit(
                    'modal', name, f'aux({s.low(m)})+{s.rel(v)}', base, [m], m, [s.edge(m)], [top], roles,
                    relation, checks, form, '%s [%s]' % (name, top['quote']), unresolved, head=v, reading=reading,
                    readings=f['readings'], readingRule=inf.get('readingRule'), modal=s.low(m),
                    attribute=attribute, negation=negation, negationInReading=consumed)
            elif f['rule'] == 'norm-predicate':
                p = f['head']
                reading = f['reading']
                relation, attribute, negation = MODAL_SUMO.get(reading, (None, None, None))
                top = self.clause('norm', p)
                action = (f.get('complement') or {}).get('node')
                roles = {'predicate': True, 'bearer': bool(f['subjects']) and top['subjectExplicit'],
                         'action': action is not None}
                checks = self.relation_check(relation, attribute) + self.frame_checks(inf) + \
                    ([self.formula_check(action['id'])] if action else [])
                form = modal_form(reading, relation, attribute, negation, ', '.join(top['subjects']) or '?',
                                  '<%s>' % (action['lemma'] if action else '?'))
                self.emit('modal', reading.upper(), f'norm-predicate({s.low(p)})', EXPLICIT, [p], p, [s.edge(p)], [top],
                          roles, relation, checks, form, '%s [%s]' % (reading.upper(), top['quote']),
                          [u for u in f['unresolved'] if u.startswith('subject-inherited')], head=p, reading=reading)

    def negations(self):
        s = self.s
        fronted = sorted(s.w)[1] if s.neg_extra else None
        for i in sorted(s.w):
            cues = [k for k in s.ch(i, 'advmod', 'neg', 'aux', 'det') if s.lem(k) in NEGATORS or s.low(k) in NEGATORS]
            if i in s.neg_extra and fronted is not None:
                cues.append(fronted)
            if not cues or s.w[i]['upos'] not in ('VERB', 'AUX', 'ADJ', 'NOUN', 'PROPN'):
                continue
            k = cues[0]
            # the clause the negator sits in, without the subordinator or complementizer that introduces it:
            # “If you do not agree” negates “you do not agree”, and the “If” is another operator's cue
            top = self.clause('operand', i, without=s.ch(i, 'mark'))
            roles = {'cue': True, 'operand-predicate': self.predicate(i)}
            self.at[i]['neg'] = self.emit('negation', 'NOT', f'{s.rel(k)}({s.low(k)})+{s.rel(i)}', EXPLICIT, [k], k,
                                          [s.edge(k)], [top], roles, None, [self.formula_check(i)],
                                          '(not <%s>)' % top['predicate'],
                                          'NOT <%s> in [%s]' % (top['predicate'], top['quote']),
                                          ['negation-scope-not-established'], head=i)

    def coordinations(self):
        s = self.s
        for h in sorted(s.w):
            if not self.predicate(h):
                continue
            conjs = [k for k in s.ch(h, 'conj') if s.w[k]['upos'] == 'VERB' or s.ch(k, 'cop')]
            if not conjs:
                continue
            ccs = [(c, COORDINATORS[s.low(c)]) for k in conjs for c in s.ch(k, 'cc') if s.low(c) in COORDINATORS]
            kinds = {name for _, name in ccs}
            operator = ccs[-1][1] if ccs else 'AND'
            cls = 'disjunction' if operator == 'OR' else 'conjunction'
            operands = [self.clause('conjunct', h, first_conjunct=True)] + [self.clause('conjunct', k) for k in conjs]
            roles = {'coordinator': bool(ccs), 'first-predicate': True, 'other-predicates': True,
                     'single-coordinator': len(kinds) <= 1}
            unresolved = ['coordination-scope-not-established'] + (['mixed-coordination'] if len(kinds) > 1 else []) + \
                (['inclusive-or-exclusive-not-established'] if operator == 'OR' else [])
            key = ccs[-1][0] if ccs else h
            preds = ' '.join('<%s>' % o['predicate'] for o in operands)
            self.at[h]['coord'] = (self.emit(
                cls, operator, 'cc(%s)+conj' % (s.low(key) if ccs else 'asyndetic'), AMBIGUOUS if operator == 'OR' else EXPLICIT,
                [c for c, _ in ccs] or [h], key, [s.edge(k) for k in conjs] + [s.edge(c) for c, _ in ccs], operands, roles,
                None, [self.formula_check(o['head']) for o in operands], f'({operator.lower()} {preds})',
                (' %s ' % operator).join('[%s]' % o['quote'] for o in operands), unresolved, head=h), conjs)

    # -- the sentence's composed form ------------------------------------------------------
    def atom(self, v) -> str:
        s = self.s
        ids, _ = s.subjects(v)
        ids = [k for k in ids if not s.rel(k).startswith('csubj')]   # a clausal subject is nested below, not named
        args = [s.lem(k) for k in ids] + [s.lem(k) for k in s.ch(v, *OBJECTS)]
        inner = ' '.join([s.verb_lemma(v)] + args)
        comps = [self.form(c) for c in s.ch(v, 'ccomp', 'xcomp', 'csubj')]
        return '<%s>' % inner if not comps else '(<%s> %s)' % (inner, ' '.join(comps))

    def core(self, v) -> str:
        """Predicate with its own causal reading, negation and modal: (modal (not atom))."""
        info = self.at[v]
        e = info['causal']['sumo']['form'] if info['causal'] else self.atom(v)
        m = info['modal']
        if info['neg'] and not (m and m['negationInReading']):
            e = f'(not {e})'
        if m:
            e = modal_form(m['reading'], m['sumo']['relation'], m['attribute'], m['negation'],
                           ', '.join(m['operands'][0]['subjects']) or '?', e, m['modal'])
        return e

    def form(self, v) -> str:
        """Scope convention, not a finding: coordination inside, then cased nominals, then subordinate clauses
        outermost in source order (condition over norm). Every operator records that its scope is not established."""
        info = self.at[v]
        e = self.core(v)
        if info['coord']:
            op, conjs = info['coord']
            e = '(%s %s)' % (op['operator'].lower(), ' '.join([e] + [self.form(k) for k in conjs]))
        for op in info['pps']:
            x = '<%s>' % op['operands'][0]['lemma']
            e = f'(causes {x} {e})' if op['class'] == 'reason' else f'(=> {x} {e})'
        for op, k in sorted(info['subs'], key=lambda t: -t[1]):
            a = self.form(k)
            cls = op['class']
            if cls == 'temporal':
                e = TEMPORAL.get(op['cue']['keyText'].lower(), TEMPORAL_DEFAULT)[1].format(a=a, c=e)
                continue
            e = {'condition': f'(=> {a} {e})', 'exception': f'(=> (not {a}) {e})',
                 'reason': f'(causesProposition {a} {e})', 'concession': f'(and {a} {e})',
                 'purpose': f'(hasPurpose {e} {a})'}[cls]
        return e

    def run(self):
        self.subordinators()
        self.purposes()
        self.causal_predicates()
        self.cased_nominals()
        self.modals()
        self.negations()
        self.coordinations()
        self.ops.sort(key=lambda o: (o['cue']['keyBegin'], o['class'], o['operator']))
        for n, op in enumerate(self.ops):
            op['id'] = 'op-%d-%d' % (self.record['index'], n)
        roots = [i for i in sorted(self.s.w) if self.s.w[i]['head'] == 0]
        logic = {'sentence': self.record['index'], 'begin': self.record['begin'], 'end': self.record['end'],
                 'quote': self.record['quote'], 'form': self.form(roots[0]) if roots else None,
                 'operators': [op['id'] for op in self.ops],
                 'scope': 'convention: subordinate clauses outermost; not established by the tree'}
        return self.ops, logic


def extract(record: dict, text: str, typer=None, ctx=None, frames=(), u16=None):
    """(operators, logic form) of one parsed sentence."""
    return Extractor(record, text, typer, ctx, frames, u16).run()


def masks(ops: list, record: dict) -> list:
    """One masked-cue request per operator whose cue word has a lexical class: the sentence, the cue's span
    inside it, and the class lexicon. The masked-language-model lane fills these; it decides nothing."""
    out = []
    for op in ops:
        cue = op['cue']
        if not cue['lexicalClass']:
            continue
        out.append({'operator': op['id'], 'class': op['class'], 'operatorName': op['operator'],
                    'cueClass': cue['lexicalClass'], 'cue': cue['keyText'], 'text': record['quote'],
                    'begin': cue['keyBegin'] - record['begin'], 'end': cue['keyEnd'] - record['begin'],
                    'sourceBegin': cue['keyBegin'], 'sourceEnd': cue['keyEnd']})
    return out


def summarize(ops: list) -> dict:
    def mean(values):
        values = [v for v in values if v is not None]
        return round(sum(values) / len(values), 4) if values else None
    by_class = defaultdict(list)
    for op in ops:
        by_class[op['class']].append(op)
    return {'operators': len(ops),
            'byOperator': dict(sorted(Counter(op['operator'] for op in ops).items())),
            'byConstruction': dict(sorted(Counter(op['construction'] for op in ops).items())),
            'byClass': {c: {'count': len(v),
                            'deterministicMean': mean(o['weights']['deterministic']['value'] for o in v),
                            'ontologicalMean': mean(o['weights']['ontological']['value'] for o in v),
                            'ontologicalAnySenseMean': mean(o['weights']['ontological']['anySense'] for o in v),
                            'fullyResolved': sum(1 for o in v if not o['weights']['deterministic']['unresolvedRoles']),
                            'disjoint': sum(1 for o in v if o['weights']['ontological']['disjoint'])}
                        for c, v in sorted(by_class.items())},
            'checkStatuses': dict(sorted(Counter(c['status'] for op in ops for c in op['sumo']['checks']).items())),
            'levels': {LEVELS[b]: {'base': b, 'note': LEVEL_NOTES[LEVELS[b]]} for b in (LEXICAL, EXPLICIT, AMBIGUOUS)},
            'admitted': 0}
