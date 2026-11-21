"""Unit tests for operator logic over hand-built dependency trees (no Stanza, no SUMO corpus needed).

    cd utils/stanza && python3 -m unittest discover -s tests

The larger trees are Stanza 1.15.0's own parses of the cited sentences, copied edge for edge.
"""
import os
import sys
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), '..'))
sys.path.insert(0, os.path.dirname(__file__))

from legalframes import frames as fr  # noqa: E402
from legalframes import operators as ops  # noqa: E402
from legalframes.infer import Context, Typer, infer_frame  # noqa: E402
from test_legalframes import FakeLexicon, sumo_from  # noqa: E402

KIF = '''
(partition Entity Physical Abstract)
(partition Physical Object Process)
(subclass Object Physical)
(subclass Process Physical)
(subclass AutonomousAgent Object)
(subclass CognitiveAgent AutonomousAgent)
(subclass Human CognitiveAgent)
(subclass Attribute Abstract)
(subclass NormativeAttribute Attribute)
(subclass IntentionalProcess Process)
(subclass FinancialTransaction IntentionalProcess)
(subclass Postponing IntentionalProcess)
(subclass Cooperation IntentionalProcess)
(subclass Stopping Process)
(subclass Closing Process)
(subclass Getting Process)
(subclass Helping IntentionalProcess)
(subclass Regulation Abstract)
(subclass Quantity Abstract)
(instance causes BinaryPredicate)
(domain causes 1 Process)
(domain causes 2 Process)
(instance hasPurpose BinaryPredicate)
(instance earlier BinaryPredicate)
(instance holdsRight BinaryPredicate)
(instance holdsObligation BinaryPredicate)
(instance modalAttribute BinaryPredicate)
(instance Possibility NormativeAttribute)
(instance Likely NormativeAttribute)
(domain holdsObligation 1 CognitiveAgent)
(domain holdsRight 1 CognitiveAgent)
'''
LEXICON = FakeLexicon({
    ('transaction', 'n'): [('transaction.n.01', 'FinancialTransaction')],
    ('transactions', 'n'): [('transaction.n.01', 'FinancialTransaction')],
    ('delay', 'n'): [('delay.n.01', 'Postponing')],
    ('delays', 'n'): [('delay.n.01', 'Postponing')],
    ('loss', 'n'): [('loss.n.01', 'Quantity')],
    ('protocol', 'n'): [('protocol.n.01', 'Regulation')],
    ('protocols', 'n'): [('protocol.n.01', 'Regulation')],
    ('agree', 'v'): [('agree.v.01', 'Cooperation')],
    ('terminate', 'v'): [('end.v.01', 'Stopping')],
    ('close', 'v'): [('close.v.01', 'Closing')],
    ('retrieve', 'v'): [('recover.v.01', 'Getting')],
    ('share', 'v'): [('share.v.01', 'Getting')],
    ('facilitate', 'v'): [('facilitate.v.01', 'Helping')],
})


def build(text, rows):
    """rows: (text, lemma, upos, xpos, head, deprel); offsets are found left to right in the sentence."""
    words, at = [], 0
    for i, (tok, lemma, upos, xpos, head, deprel) in enumerate(rows, 1):
        at = text.index(tok, at)
        words.append({'id': i, 'text': tok, 'lemma': lemma, 'upos': upos, 'xpos': xpos, 'feats': None, 'head': head,
                      'deprel': deprel, 'begin': at, 'end': at + len(tok), 'ner': 'O', 'mwt': False})
        at += len(tok)
    return {'index': 0, 'begin': 0, 'end': len(text), 'u16Begin': 0, 'u16End': len(text), 'quote': text,
            'section': None, 'words': words, 'entities': []}


def typer():
    return Typer(sumo_from(KIF), LEXICON)


def one(found, **want):
    hits = [o for o in found if all(o[k] == v for k, v in want.items())]
    assert len(hits) == 1, (want, [(o['class'], o['operator'], o['construction']) for o in found])
    return hits[0]


REMEDY = ('If you do not agree with any such modification, your sole and exclusive remedy is to terminate your use '
          'of the Services and close your account (as defined below).')
REMEDY_ROWS = [
    ('If', 'if', 'SCONJ', 'IN', 5, 'mark'), ('you', 'you', 'PRON', 'PRP', 5, 'nsubj'), ('do', 'do', 'AUX', 'VBP', 5, 'aux'),
    ('not', 'not', 'PART', 'RB', 5, 'advmod'), ('agree', 'agree', 'VERB', 'VB', 18, 'advcl'),
    ('with', 'with', 'ADP', 'IN', 9, 'case'), ('any', 'any', 'DET', 'DT', 9, 'det'), ('such', 'such', 'ADJ', 'JJ', 9, 'amod'),
    ('modification', 'modification', 'NOUN', 'NN', 5, 'obl'), (',', ',', 'PUNCT', ',', 5, 'punct'),
    ('your', 'your', 'PRON', 'PRP$', 15, 'nmod:poss'), ('sole', 'sole', 'ADJ', 'JJ', 15, 'amod'),
    ('and', 'and', 'CCONJ', 'CC', 14, 'cc'), ('exclusive', 'exclusive', 'ADJ', 'JJ', 12, 'conj'),
    ('remedy', 'remedy', 'NOUN', 'NN', 18, 'nsubj:outer'), ('is', 'be', 'AUX', 'VBZ', 18, 'cop'),
    ('to', 'to', 'PART', 'TO', 18, 'mark'), ('terminate', 'terminate', 'VERB', 'VB', 0, 'root'),
    ('your', 'your', 'PRON', 'PRP$', 20, 'nmod:poss'), ('use', 'use', 'NOUN', 'NN', 18, 'obj'),
    ('of', 'of', 'ADP', 'IN', 23, 'case'), ('the', 'the', 'DET', 'DT', 23, 'det'), ('Services', 'service', 'NOUN', 'NNS', 20, 'nmod'),
    ('and', 'and', 'CCONJ', 'CC', 25, 'cc'), ('close', 'close', 'VERB', 'VB', 18, 'conj'),
    ('your', 'your', 'PRON', 'PRP$', 27, 'nmod:poss'), ('account', 'account', 'NOUN', 'NN', 25, 'obj'),
    ('(', '(', 'PUNCT', '-LRB-', 30, 'punct'), ('as', 'as', 'SCONJ', 'IN', 30, 'mark'),
    ('defined', 'define', 'VERB', 'VBN', 25, 'advcl'), ('below', 'below', 'ADV', 'RB', 30, 'advmod'),
    (')', ')', 'PUNCT', '-RRB-', 30, 'punct'), ('.', '.', 'PUNCT', '.', 18, 'punct')]

DELAYS = 'This could result in significant support response time delays.'
DELAYS_ROWS = [
    ('This', 'this', 'PRON', 'DT', 3, 'nsubj'), ('could', 'could', 'AUX', 'MD', 3, 'aux'), ('result', 'result', 'VERB', 'VB', 0, 'root'),
    ('in', 'in', 'ADP', 'IN', 9, 'case'), ('significant', 'significant', 'ADJ', 'JJ', 9, 'amod'),
    ('support', 'support', 'NOUN', 'NN', 9, 'compound'), ('response', 'response', 'NOUN', 'NN', 8, 'compound'),
    ('time', 'time', 'NOUN', 'NN', 9, 'compound'), ('delays', 'delay', 'NOUN', 'NNS', 3, 'obl'), ('.', '.', 'PUNCT', '.', 3, 'punct')]

LOSS = 'Any attempted transactions in such items will result in loss of the item.'
LOSS_ROWS = [
    ('Any', 'any', 'DET', 'DT', 3, 'det'), ('attempted', 'attempt', 'VERB', 'VBN', 3, 'amod'),
    ('transactions', 'transaction', 'NOUN', 'NNS', 8, 'nsubj'), ('in', 'in', 'ADP', 'IN', 6, 'case'),
    ('such', 'such', 'ADJ', 'JJ', 6, 'amod'), ('items', 'item', 'NOUN', 'NNS', 3, 'nmod'), ('will', 'will', 'AUX', 'MD', 8, 'aux'),
    ('result', 'result', 'VERB', 'VB', 0, 'root'), ('in', 'in', 'ADP', 'IN', 10, 'case'), ('loss', 'loss', 'NOUN', 'NN', 8, 'obl'),
    ('of', 'of', 'ADP', 'IN', 13, 'case'), ('the', 'the', 'DET', 'DT', 13, 'det'), ('item', 'item', 'NOUN', 'NN', 10, 'nmod'),
    ('.', '.', 'PUNCT', '.', 8, 'punct')]

PROTOCOLS = ('As a result of our security protocols, it may be necessary for us to retrieve private keys in order to '
             'facilitate transfers.')
PROTOCOLS_ROWS = [
    ('As', 'as', 'ADP', 'IN', 3, 'case'), ('a', 'a', 'DET', 'DT', 3, 'det'), ('result', 'result', 'NOUN', 'NN', 12, 'obl'),
    ('of', 'of', 'ADP', 'IN', 7, 'case'), ('our', 'our', 'PRON', 'PRP$', 7, 'nmod:poss'), ('security', 'security', 'NOUN', 'NN', 7, 'compound'),
    ('protocols', 'protocol', 'NOUN', 'NNS', 3, 'nmod'), (',', ',', 'PUNCT', ',', 3, 'punct'), ('it', 'it', 'PRON', 'PRP', 12, 'expl'),
    ('may', 'may', 'AUX', 'MD', 12, 'aux'), ('be', 'be', 'AUX', 'VB', 12, 'cop'), ('necessary', 'necessary', 'ADJ', 'JJ', 0, 'root'),
    ('for', 'for', 'SCONJ', 'IN', 16, 'mark'), ('us', 'we', 'PRON', 'PRP', 16, 'nsubj'), ('to', 'to', 'PART', 'TO', 16, 'mark'),
    ('retrieve', 'retrieve', 'VERB', 'VB', 12, 'csubj'), ('private', 'private', 'ADJ', 'JJ', 18, 'amod'),
    ('keys', 'key', 'NOUN', 'NNS', 16, 'obj'), ('in', 'in', 'ADP', 'IN', 22, 'mark'), ('order', 'order', 'NOUN', 'NN', 19, 'fixed'),
    ('to', 'to', 'PART', 'TO', 22, 'mark'), ('facilitate', 'facilitate', 'VERB', 'VB', 16, 'advcl'),
    ('transfers', 'transfer', 'NOUN', 'NNS', 22, 'obj'), ('.', '.', 'PUNCT', '.', 12, 'punct')]

UNLESS = 'Unless you object, we will close the account before the term ends.'
UNLESS_ROWS = [
    ('Unless', 'unless', 'SCONJ', 'IN', 3, 'mark'), ('you', 'you', 'PRON', 'PRP', 3, 'nsubj'), ('object', 'object', 'VERB', 'VBP', 7, 'advcl'),
    (',', ',', 'PUNCT', ',', 3, 'punct'), ('we', 'we', 'PRON', 'PRP', 7, 'nsubj'), ('will', 'will', 'AUX', 'MD', 7, 'aux'),
    ('close', 'close', 'VERB', 'VB', 0, 'root'), ('the', 'the', 'DET', 'DT', 9, 'det'), ('account', 'account', 'NOUN', 'NN', 7, 'obj'),
    ('before', 'before', 'SCONJ', 'IN', 13, 'mark'), ('the', 'the', 'DET', 'DT', 12, 'det'), ('term', 'term', 'NOUN', 'NN', 13, 'nsubj'),
    ('ends', 'end', 'VERB', 'VBZ', 7, 'advcl'), ('.', '.', 'PUNCT', '.', 7, 'punct')]

PROVIDED = 'You use the services provided by Coinbase when permitted.'
PROVIDED_ROWS = [
    ('You', 'you', 'PRON', 'PRP', 2, 'nsubj'), ('use', 'use', 'VERB', 'VBP', 0, 'root'), ('the', 'the', 'DET', 'DT', 4, 'det'),
    ('services', 'service', 'NOUN', 'NNS', 2, 'obj'), ('provided', 'provide', 'VERB', 'VBN', 4, 'acl'), ('by', 'by', 'ADP', 'IN', 7, 'case'),
    ('Coinbase', 'Coinbase', 'PROPN', 'NNP', 5, 'obl:agent'), ('when', 'when', 'ADV', 'WRB', 9, 'advmod'),
    ('permitted', 'permit', 'VERB', 'VBN', 2, 'advcl'), ('.', '.', 'PUNCT', '.', 2, 'punct')]

SUSPEND = 'We may suspend or terminate your account.'
SUSPEND_ROWS = [
    ('We', 'we', 'PRON', 'PRP', 3, 'nsubj'), ('may', 'may', 'AUX', 'MD', 3, 'aux'), ('suspend', 'suspend', 'VERB', 'VB', 0, 'root'),
    ('or', 'or', 'CCONJ', 'CC', 5, 'cc'), ('terminate', 'terminate', 'VERB', 'VB', 3, 'conj'),
    ('your', 'your', 'PRON', 'PRP$', 7, 'nmod:poss'), ('account', 'account', 'NOUN', 'NN', 5, 'obj'), ('.', '.', 'PUNCT', '.', 3, 'punct')]


SHARE = 'You will not share your password.'
SHARE_ROWS = [
    ('You', 'you', 'PRON', 'PRP', 4, 'nsubj'), ('will', 'will', 'AUX', 'MD', 4, 'aux'), ('not', 'not', 'PART', 'RB', 4, 'advmod'),
    ('share', 'share', 'VERB', 'VB', 0, 'root'), ('your', 'your', 'PRON', 'PRP$', 6, 'nmod:poss'),
    ('password', 'password', 'NOUN', 'NN', 4, 'obj'), ('.', '.', 'PUNCT', '.', 4, 'punct')]

UNAVAILABLE = 'Access may be unavailable.'
UNAVAILABLE_ROWS = [
    ('Access', 'access', 'NOUN', 'NN', 4, 'nsubj'), ('may', 'may', 'AUX', 'MD', 4, 'aux'), ('be', 'be', 'AUX', 'VB', 4, 'cop'),
    ('unavailable', 'unavailable', 'ADJ', 'JJ', 0, 'root'), ('.', '.', 'PUNCT', '.', 4, 'punct')]

# Stanza's own attachment of “for any losses resulting from or arising out of delays”: the first preposition is
# stranded under the first participle, and the shared oblique hangs from that participle with the second's case.
ARISING = 'Coinbase is not liable for losses resulting from or arising out of delays.'
ARISING_ROWS = [
    ('Coinbase', 'Coinbase', 'PROPN', 'NNP', 4, 'nsubj'), ('is', 'be', 'AUX', 'VBZ', 4, 'cop'), ('not', 'not', 'PART', 'RB', 4, 'advmod'),
    ('liable', 'liable', 'ADJ', 'JJ', 0, 'root'), ('for', 'for', 'ADP', 'IN', 6, 'case'), ('losses', 'loss', 'NOUN', 'NNS', 4, 'obl'),
    ('resulting', 'result', 'VERB', 'VBG', 6, 'acl'), ('from', 'from', 'ADP', 'IN', 7, 'obl'), ('or', 'or', 'CCONJ', 'CC', 10, 'cc'),
    ('arising', 'arise', 'VERB', 'VBG', 7, 'conj'), ('out', 'out', 'ADP', 'IN', 13, 'case'), ('of', 'of', 'ADP', 'IN', 11, 'fixed'),
    ('delays', 'delay', 'NOUN', 'NNS', 7, 'obl'), ('.', '.', 'PUNCT', '.', 4, 'punct')]


def modal_frames(record, text, t, ctx):
    return [(f, infer_frame(f, t, ctx)) for f in fr.frames(record, text) if f['rule'] in ('modal-action', 'norm-predicate')]


class WeightTests(unittest.TestCase):
    def test_deterministic_weight_is_base_times_resolved_roles(self):
        w = ops.deterministic_weight(ops.EXPLICIT, {'cue': True, 'a': True, 'b': False, 'c': True})
        self.assertEqual((w['value'], w['level'], w['resolved'], w['required'], w['unresolvedRoles']),
                         (0.6, 'explicit', 3, 4, ['b']))

    def test_ontological_weight_counts_entailed_checks_and_flags_disjoint(self):
        w = ops.ontological_weight([{'status': 'entailed'}, {'status': 'disjoint'}, None, {'status': 'untyped'}])
        self.assertEqual((w['value'], w['entailed'], w['total'], w['disjoint']), (0.3333, 1, 3, True))
        self.assertEqual((w['anySense'], w['otherSense']), (0.3333, 0))            # no other sense on record
        self.assertIsNone(ops.ontological_weight([])['value'])
        self.assertIsNone(ops.ontological_weight([])['anySense'])

    def test_any_sense_counts_a_failed_check_another_mapped_sense_would_pass(self):
        w = ops.ontological_weight([
            {'status': 'entailed', 'otherSensesEntailed': ['x.n.02→Process']},      # already entailed: counted once
            {'status': 'not-entailed', 'otherSensesEntailed': ['transaction.n.01→Transaction']},
            {'status': 'not-entailed', 'otherSenseIsProcess': True},
            {'status': 'disjoint', 'otherSensesEntailed': []}])
        self.assertEqual((w['value'], w['anySense'], w['entailed'], w['otherSense'], w['total']), (0.25, 0.75, 1, 2, 4))
        self.assertTrue(w['disjoint'])                                              # the first-sense finding stands

    def test_every_cue_word_has_one_lexical_class(self):
        words = [w for ws in ops.CUE_CLASSES.values() for w in ws]
        self.assertEqual(len(words), len(set(words)))


class ConstructionTests(unittest.TestCase):
    def test_negated_conditional_over_a_coordinated_consequent(self):
        found, logic = ops.extract(build(REMEDY, REMEDY_ROWS), REMEDY, typer(), Context([]))
        cond = one(found, **{'class': 'condition'})
        self.assertEqual((cond['operator'], cond['construction']), ('IMPLIES', 'mark(if)+advcl'))
        self.assertEqual(REMEDY[cond['cue']['begin']:cond['cue']['end']], 'If')
        self.assertEqual(cond['edges'], [[18, 5, 'advcl'], [5, 1, 'mark']])
        antecedent, consequent = cond['operands']
        self.assertEqual(antecedent['quote'], 'you do not agree with any such modification')   # the cue is not its clause
        self.assertTrue(antecedent['negated'])
        self.assertEqual(consequent['predicate'], 'terminate')
        det = cond['weights']['deterministic']
        self.assertEqual((det['value'], det['resolved'], det['required']), (0.8, 6, 6))
        self.assertEqual(cond['weights']['ontological']['value'], 1.0)   # agree and terminate both have a Process sense
        neg = one(found, **{'class': 'negation'})
        self.assertEqual((neg['cue']['keyText'], neg['operands'][0]['predicate']), ('not', 'agree'))
        self.assertEqual(neg['operands'][0]['quote'], 'you do not agree with any such modification')   # without “If”
        self.assertEqual(neg['articulation'], 'NOT <agree> in [you do not agree with any such modification]')
        conj = one(found, **{'class': 'conjunction'})
        self.assertEqual([o['predicate'] for o in conj['operands']], ['terminate', 'close'])
        self.assertEqual(conj['operands'][0]['quote'], 'your sole and exclusive remedy is to terminate your use of the Services')
        self.assertEqual(logic['form'], '(=> (not <agree you>) (and <terminate remedy use> <close remedy account>))')
        self.assertFalse(any(o['admitted'] for o in found))

    def test_adjective_coordination_and_reference_clause_are_not_operators(self):
        found, _ = ops.extract(build(REMEDY, REMEDY_ROWS), REMEDY)
        self.assertEqual(sorted(o['class'] for o in found), ['condition', 'conjunction', 'negation'])

    def test_causal_verb_reads_its_oblique_as_the_effect(self):
        found, logic = ops.extract(build(DELAYS, DELAYS_ROWS), DELAYS, typer(), Context([]))
        cause = one(found, **{'class': 'cause'})
        self.assertEqual((cause['operator'], cause['construction']), ('CAUSES', 'result+obl(in)'))
        self.assertEqual([o['role'] for o in cause['operands']], ['cause', 'effect'])
        self.assertEqual(cause['operands'][1]['quote'], 'significant support response time delays')
        det = cause['weights']['deterministic']
        self.assertEqual((det['value'], det['unresolvedRoles']), (0.675, ['cause']))    # “This” needs an antecedent
        self.assertIn('cause-anaphor-or-missing', cause['unresolved'])
        onto = cause['weights']['ontological']
        self.assertEqual((onto['entailed'], onto['total']), (2, 3))                    # relation, effect; cause untyped
        self.assertEqual(logic['form'], '(causes <This> <significant support response time delays>)')

    def test_effect_outside_the_domain_of_causes_is_reported_disjoint(self):
        found, _ = ops.extract(build(LOSS, LOSS_ROWS), LOSS, typer(), Context([]))
        cause = one(found, **{'class': 'cause'})
        by_role = {c['role']: c for c in cause['sumo']['checks']}
        self.assertEqual((by_role['cause']['status'], by_role['cause']['term']), ('entailed', 'FinancialTransaction'))
        self.assertEqual((by_role['effect']['status'], by_role['effect']['required']), ('disjoint', 'Process'))
        self.assertTrue(cause['weights']['ontological']['disjoint'])
        self.assertEqual(cause['weights']['deterministic']['value'], 0.9)

    def test_a_plural_only_first_sense_lowers_the_weight_and_any_sense_recovers_it(self):
        """WordNet lists “transactions” as the written record of proceedings before the Transaction: the first
        mapped sense fails `causes`, the second satisfies it, and the weight reports both."""
        lexicon = FakeLexicon({**LEXICON.table,
                               ('transactions', 'n'): [('minutes.n.01', 'Regulation'), ('transaction.n.01', 'FinancialTransaction')],
                               ('loss', 'n'): [('loss.n.01', 'Quantity'), ('loss.n.02', 'Stopping')]})
        found, _ = ops.extract(build(LOSS, LOSS_ROWS), LOSS, Typer(sumo_from(KIF), lexicon), Context([]))
        cause = one(found, **{'class': 'cause'})
        by_role = {c['role']: c for c in cause['sumo']['checks']}
        self.assertEqual((by_role['cause']['status'], by_role['cause']['term']), ('disjoint', 'Regulation'))
        self.assertEqual(by_role['cause']['otherSensesEntailed'], ['transaction.n.01→FinancialTransaction'])
        self.assertEqual(by_role['effect']['otherSensesEntailed'], ['loss.n.02→Stopping'])
        onto = cause['weights']['ontological']
        self.assertEqual((onto['value'], onto['anySense'], onto['otherSense']), (0.3333, 1.0, 2))

    def test_fixed_preposition_cause_and_explicit_purpose(self):
        found, logic = ops.extract(build(PROTOCOLS, PROTOCOLS_ROWS), PROTOCOLS, typer(), Context([]))
        reason = one(found, **{'class': 'reason'})
        self.assertEqual(reason['construction'], 'case(as a result of)+obl')
        self.assertEqual(reason['cue']['quote'], 'As a result of')
        self.assertEqual(reason['operands'][0]['quote'], 'our security protocols')
        self.assertEqual(reason['cue']['lexicalClass'], 'causation')
        purpose = one(found, **{'class': 'purpose'})
        self.assertEqual((purpose['construction'], purpose['cue']['quote'], purpose['cue']['keyText']),
                         ('mark(in order to)+advcl', 'in order to', 'order'))
        self.assertEqual([o['predicate'] for o in purpose['operands']], ['retrieve', 'facilitate'])
        self.assertEqual(purpose['weights']['deterministic']['value'], 0.8)
        # the infinitive's subject is controlled, not stated: the tree gives “facilitate” no subject of its own
        self.assertEqual(logic['form'], '(causes <protocol> (<necessary> (hasPurpose <retrieve we key> <facilitate transfer>)))')

    def test_exception_and_temporal_subordinators(self):
        found, logic = ops.extract(build(UNLESS, UNLESS_ROWS), UNLESS, typer(), Context([]))
        exc = one(found, **{'class': 'exception'})
        self.assertEqual((exc['operator'], exc['sumo']['form']), ('UNLESS', '(=> (not <object>) <close>)'))
        before = one(found, **{'class': 'temporal'})
        self.assertEqual((before['operator'], before['sumo']['relation']), ('BEFORE', 'earlier'))
        self.assertEqual(before['sumo']['form'], '(earlier (WhenFn <close>) (WhenFn <end>))')
        self.assertEqual(logic['form'], '(=> (not <object you>) (earlier (WhenFn <close we account>) (WhenFn <end term>)))')

    def test_participle_provided_is_not_a_condition_and_elliptical_when_is_unresolved(self):
        found, _ = ops.extract(build(PROVIDED, PROVIDED_ROWS), PROVIDED)
        self.assertEqual([(o['class'], o['cue']['keyText']) for o in found], [('temporal', 'when')])
        when = found[0]
        self.assertEqual(when['weights']['deterministic']['level'], 'ambiguous')
        self.assertEqual(when['weights']['deterministic']['unresolvedRoles'], ['subordinate-subject'])
        self.assertIn('cue-reading-not-established:when', when['unresolved'])
        self.assertIsNone(when['weights']['ontological']['value'])                   # no typer, no SUMO checks

    def test_modal_reading_comes_from_the_frame_and_or_stays_ambiguous(self):
        record = build(SUSPEND, SUSPEND_ROWS)
        t, ctx = typer(), Context([])
        found, logic = ops.extract(record, SUSPEND, t, ctx, modal_frames(record, SUSPEND, t, ctx))
        modal = [o for o in found if o['class'] == 'modal']
        self.assertEqual(len(modal), 2)                                               # the conjunct inherits “may”
        # “We” without the party-definition preamble is not an agent: the rule falls back to possibility
        self.assertEqual({o['reading'] for o in modal}, {'possibility'})
        self.assertEqual(modal[0]['readingRule'], 'non-agentive-subject')
        self.assertEqual(modal[0]['weights']['deterministic']['base'], ops.AMBIGUOUS)
        disj = one(found, **{'class': 'disjunction'})
        self.assertEqual((disj['operator'], disj['weights']['deterministic']['value']), ('OR', 0.5))
        self.assertIn('inclusive-or-exclusive-not-established', disj['unresolved'])
        self.assertEqual(logic['form'], '(or (modalAttribute <suspend we> Possibility) (modalAttribute <terminate we account> Possibility))')

    def test_coordinated_causal_participles_share_one_oblique(self):
        found, _ = ops.extract(build(ARISING, ARISING_ROWS), ARISING, typer(), Context([]))
        causes = sorted((o for o in found if o['class'] == 'cause'), key=lambda o: o['cue']['keyBegin'])
        self.assertEqual([o['construction'] for o in causes], ['result+obl(from)', 'arise+obl(out of)'])
        for o, how in zip(causes, ('stranded', 'shared')):
            self.assertEqual({r['role']: r['quote'] for r in o['operands']}, {'cause': 'delays', 'effect': 'losses'})
            self.assertIn(f'argument-{how}-by-coordination', o['unresolved'])
            self.assertEqual(o['weights']['deterministic']['value'], 0.675)          # 0.9 x 3 of 4 roles
            self.assertEqual(o['weights']['deterministic']['unresolvedRoles'], ['argument-attachment'])

    def test_a_negative_reading_carries_the_negator_once(self):
        record = build(SHARE, SHARE_ROWS)
        t, ctx = typer(), Context([])
        found, logic = ops.extract(record, SHARE, t, ctx, modal_frames(record, SHARE, t, ctx))
        modal = one(found, **{'class': 'modal'})
        self.assertEqual((modal['operator'], modal['readingRule'], modal['negationInReading']),
                         ('NEGATIVE-COMMITMENT', 'agentive-subject', True))
        self.assertEqual(modal['sumo']['form'], '(holdsObligation <You> (not <share>))')
        self.assertEqual(modal['weights']['ontological']['value'], 1.0)              # relation, bearer, action
        self.assertEqual(len([o for o in found if o['class'] == 'negation']), 1)     # the negator is still a cue in the tree
        self.assertEqual(logic['form'], '(holdsObligation <You> (not <share you password>))')

    def test_modal_over_a_copular_predicate_stays_unread(self):
        record = build(UNAVAILABLE, UNAVAILABLE_ROWS)
        t, ctx = typer(), Context([])
        found, logic = ops.extract(record, UNAVAILABLE, t, ctx, modal_frames(record, UNAVAILABLE, t, ctx))
        modal = one(found, **{'class': 'modal'})
        self.assertEqual((modal['operator'], modal['reading'], modal['readings']), ('MODAL', None, ['permission', 'possibility']))
        self.assertIn('modal-reading-not-selected', modal['unresolved'])
        self.assertEqual(modal['weights']['deterministic']['value'], 0.375)          # 0.5 x 3 of 4: no reading selected
        self.assertEqual(logic['form'], '(modal:may <unavailable access>)')

    def test_masks_cover_classed_cues_with_spans_inside_the_sentence(self):
        record = build(REMEDY, REMEDY_ROWS)
        found, _ = ops.extract(record, REMEDY)
        ms = ops.masks(found, record)
        self.assertEqual(sorted((m['cue'], m['cueClass']) for m in ms), [('If', 'condition'), ('and', 'conjunction'), ('not', 'negation')])
        for m in ms:
            self.assertEqual(REMEDY[m['begin']:m['end']], m['cue'])

    def test_extraction_is_deterministic(self):
        runs = [ops.extract(build(PROTOCOLS, PROTOCOLS_ROWS), PROTOCOLS, typer(), Context([])) for _ in range(2)]
        self.assertEqual(runs[0], runs[1])
        self.assertEqual(ops.summarize(runs[0][0])['admitted'], 0)


if __name__ == '__main__':
    unittest.main()
