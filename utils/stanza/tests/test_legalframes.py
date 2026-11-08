"""Unit tests for the legal-frame pipeline that need neither Stanza nor the SUMO corpus.

    cd utils/stanza && python3 -m unittest discover -s tests
"""
import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), '..'))

from legalframes import frames as fr  # noqa: E402
from legalframes.infer import Context, Definitions, Typer, infer_frame  # noqa: E402
from legalframes.sumo import Sumo, read_forms  # noqa: E402
from legalframes.text import line_blocks, paragraph_blocks, parse_view, utf16_offsets, view_to_source  # noqa: E402

KIF = '''
; (subclass Organization CognitiveAgent)
(partition Entity Physical Abstract)
(partition Physical Object Process)
(subclass Object Physical)
(subclass Process Physical)
(subclass AutonomousAgent Object)
(subclass CognitiveAgent AutonomousAgent)
(subclass Human CognitiveAgent)
(subclass Organization AutonomousAgent)
(subclass Attribute Abstract)
(subclass NormativeAttribute Attribute)
(subclass Proposition Abstract)
(subclass Agreement Proposition)
(subclass Account Object)
(subclass FinancialAccount Account)
(subclass FinancialAccount Agreement)
(subclass Keeping Process)
(subclass Confining Process)
(instance possesses BinaryPredicate)
(domain possesses 1 AutonomousAgent)
(domain possesses 2 Object)
(domain holdsObligation 1 CognitiveAgent)
(domain holdsObligation 2 Formula)
(domain holdsRight 1 CognitiveAgent)
(domain confersRight 2 CognitiveAgent)
(domain confersObligation 2 CognitiveAgent)
(documentation possesses EnglishLanguage "Ownership (of an &%Object); \\"quoted\\" (parens) ; not a comment")
'''


def sumo_from(text):
    with tempfile.TemporaryDirectory() as d:
        p = os.path.join(d, 'Tiny.kif')
        with open(p, 'w') as f:
            f.write(text)
        return Sumo([p], d)


class FakeLexicon:
    """lemma -> [(synset, term)] in sense order."""

    def __init__(self, table):
        self.table = table

    def senses(self, lemma, pos):
        return tuple({'rank': i + 1, 'synset': syn, 'offset': '0', 'gloss': syn,
                      'mappings': [{'term': term, 'relation': 'subsumed'}]}
                     for i, (syn, term) in enumerate(self.table.get((lemma, pos), [])))

    def has_entry(self, lemma, pos='n'):
        return bool(self.table.get((lemma, pos)))


def word(i, text, lemma, upos, xpos, head, deprel, begin, ner='O', feats=None):
    return {'id': i, 'text': text, 'lemma': lemma, 'upos': upos, 'xpos': xpos, 'feats': feats, 'head': head,
            'deprel': deprel, 'begin': begin, 'end': begin + len(text), 'ner': ner, 'mwt': False}


def sentence(text, words):
    return {'index': 0, 'begin': 0, 'end': len(text), 'u16Begin': 0, 'u16End': len(text), 'quote': text,
            'section': None, 'words': words, 'entities': []}


HOLD = 'Coinbase will hold your Digital Assets.'
HOLD_WORDS = [word(1, 'Coinbase', 'Coinbase', 'PROPN', 'NNP', 3, 'nsubj', 0, 'S-ORG'),
              word(2, 'will', 'will', 'AUX', 'MD', 3, 'aux', 9),
              word(3, 'hold', 'hold', 'VERB', 'VB', 0, 'root', 14),
              word(4, 'your', 'your', 'PRON', 'PRP$', 7, 'nmod:poss', 19),
              word(5, 'Digital', 'digital', 'ADJ', 'JJ', 7, 'amod', 24),
              word(6, 'Assets', 'asset', 'NOUN', 'NNS', 7, 'compound', 32),
              word(7, '.', '.', 'PUNCT', '.', 3, 'punct', 38)]
# The last token stands in for a noun head so the compound test stays small.
HOLD_WORDS[5] = word(6, 'Assets', 'asset', 'NOUN', 'NNS', 3, 'obj', 32)
HOLD_WORDS[4] = word(5, 'Digital', 'digital', 'ADJ', 'JJ', 6, 'amod', 24)
HOLD_WORDS[3] = word(4, 'your', 'your', 'PRON', 'PRP$', 6, 'nmod:poss', 19)

AUTH = 'I authorize Contoso to order my background report.'
AUTH_WORDS = [word(1, 'I', 'I', 'PRON', 'PRP', 2, 'nsubj', 0),
              word(2, 'authorize', 'authorize', 'VERB', 'VBP', 0, 'root', 2),
              word(3, 'Contoso', 'Contoso', 'PROPN', 'NNP', 2, 'obj', 12, 'S-ORG'),
              word(4, 'to', 'to', 'PART', 'TO', 5, 'mark', 20),
              word(5, 'order', 'order', 'VERB', 'VB', 2, 'xcomp', 23),
              word(6, 'my', 'my', 'PRON', 'PRP$', 8, 'nmod:poss', 29),
              word(7, 'background', 'background', 'NOUN', 'NN', 8, 'compound', 32),
              word(8, 'report', 'report', 'NOUN', 'NN', 5, 'obj', 43),
              word(9, '.', '.', 'PUNCT', '.', 2, 'punct', 49)]

EFFECT = 'This signature shall have legal effect.'
EFFECT_WORDS = [word(1, 'This', 'this', 'DET', 'DT', 2, 'det', 0),
                word(2, 'signature', 'signature', 'NOUN', 'NN', 4, 'nsubj', 5),
                word(3, 'shall', 'shall', 'AUX', 'MD', 4, 'aux', 15),
                word(4, 'have', 'have', 'VERB', 'VB', 0, 'root', 21),
                word(5, 'legal', 'legal', 'ADJ', 'JJ', 6, 'amod', 26),
                word(6, 'effect', 'effect', 'NOUN', 'NN', 4, 'obj', 32),
                word(7, '.', '.', 'PUNCT', '.', 4, 'punct', 38)]

MATCH = 'The name must match the name.'
MATCH_WORDS = [word(1, 'The', 'the', 'DET', 'DT', 2, 'det', 0),
               word(2, 'name', 'name', 'NOUN', 'NN', 4, 'nsubj', 4),
               word(3, 'must', 'must', 'AUX', 'MD', 4, 'aux', 9),
               word(4, 'match', 'match', 'VERB', 'VB', 0, 'root', 14),
               word(5, 'the', 'the', 'DET', 'DT', 6, 'det', 20),
               word(6, 'name', 'name', 'NOUN', 'NN', 4, 'obj', 24),
               word(7, '.', '.', 'PUNCT', '.', 4, 'punct', 28)]

SERIES = 'ownership of things; of animals; of obligations'
SERIES_WORDS = [word(1, 'ownership', 'ownership', 'NOUN', 'NN', 0, 'root', 0),
                word(2, 'of', 'of', 'ADP', 'IN', 3, 'case', 10),
                word(3, 'things', 'thing', 'NOUN', 'NNS', 1, 'nmod', 13),
                word(4, ';', ';', 'PUNCT', ':', 6, 'punct', 19),
                word(5, 'of', 'of', 'ADP', 'IN', 6, 'case', 21),
                word(6, 'animals', 'animal', 'NOUN', 'NNS', 3, 'nmod', 24),
                word(7, ';', ';', 'PUNCT', ':', 9, 'punct', 31),
                word(8, 'of', 'of', 'ADP', 'IN', 9, 'case', 33),
                word(9, 'obligations', 'obligation', 'NOUN', 'NNS', 6, 'nmod', 36)]


NOEVENT = 'IN NO EVENT SHALL COINBASE, ITS AFFILIATES BE LIABLE.'
NOEVENT_WORDS = [word(1, 'IN', 'in', 'ADP', 'IN', 3, 'case', 0),
                 word(2, 'NO', 'no', 'DET', 'DT', 3, 'det', 3),
                 word(3, 'EVENT', 'event', 'NOUN', 'NN', 5, 'obl', 6),
                 word(4, 'SHALL', 'shall', 'AUX', 'MD', 5, 'aux', 12),
                 word(5, 'COINBASE', 'coinbase', 'VERB', 'VB', 10, 'advcl', 18, 'S-ORG'),
                 word(6, ',', ',', 'PUNCT', ',', 8, 'punct', 26),
                 word(7, 'ITS', 'its', 'PRON', 'PRP$', 8, 'nmod:poss', 28),
                 word(8, 'AFFILIATES', 'affiliate', 'NOUN', 'NNS', 5, 'obj', 32),
                 word(9, 'BE', 'be', 'AUX', 'VB', 10, 'cop', 43),
                 word(10, 'LIABLE', 'liable', 'ADJ', 'JJ', 0, 'root', 46),
                 word(11, '.', '.', 'PUNCT', '.', 10, 'punct', 52)]


class TextTests(unittest.TestCase):
    def test_line_blocks_glue_inline_breaks_and_keep_offsets(self):
        text = 'Important Definitions\n:\nAs used here.\n“Digital Asset”\nmeans a digital representation of value.\n1.1. Eligibility.\nTo be eligible, you must be 18.\n'
        blocks = line_blocks(text)
        quotes = [text[b.begin:b.end] for b in blocks]
        self.assertIn('“Digital Asset”\nmeans a digital representation of value.', quotes)
        self.assertTrue(any(b.heading and text[b.begin:b.end] == '1.1. Eligibility.' for b in blocks))
        self.assertEqual(blocks[-1].section, '§ 1.1 Eligibility')
        view, starts = parse_view(text, blocks)
        back, _ = view_to_source(starts)
        i = view.index('means')
        self.assertEqual(text[back(i):back(i) + 5], 'means')

    def test_utf16_offsets_count_surrogate_pairs(self):
        self.assertEqual(utf16_offsets('a\U000F0A02b')[2], 3)

    def test_shouted_blocks_are_truecased_for_the_parser_only(self):
        text = 'You agree that Coinbase may suspend the Services and that the AAA is liable for damages.\n\nSee below. IN NO EVENT SHALL COINBASE OR THE AAA BE LIABLE FOR ANY DAMAGES.'
        blocks = paragraph_blocks(text)
        view, starts = parse_view(text, blocks)
        self.assertIn('In no event shall Coinbase or the AAA be liable for any damages.', view)
        self.assertEqual(len(view), len(text.replace('\n\n', '\n\n')))
        back, _ = view_to_source(starts)
        i = view.index('Coinbase or')
        self.assertEqual(text[back(i):back(i) + 8], 'COINBASE')

    def test_paragraph_blocks(self):
        self.assertEqual(len(paragraph_blocks('one\nline\n\n two \n')), 2)


class SumoTests(unittest.TestCase):
    def setUp(self):
        self.s = sumo_from(KIF)

    def test_strings_and_comments(self):
        forms, comments = read_forms('(a "x ) ; y" b) ; (c d)\n(e)')
        self.assertEqual([f for _, f in forms], [['a', '"x ) ; y"', 'b'], ['e']])
        self.assertEqual(len(comments), 1)

    def test_taxonomy_disjointness_and_incoherence(self):
        self.assertTrue(self.s.isa('Human', 'CognitiveAgent'))
        self.assertFalse(self.s.isa('Organization', 'CognitiveAgent'))
        self.assertIsNone(self.s.disjoint_witness('Organization', 'CognitiveAgent'))
        self.assertEqual(sorted(self.s.disjoint_witness('NormativeAttribute', 'Object')['between']), ['Abstract', 'Physical'])
        self.assertEqual(sorted(self.s.incoherent('FinancialAccount')['between']), ['Abstract', 'Physical'])
        self.assertEqual(self.s.commented_about('Organization', 'CognitiveAgent')[0][1], '(subclass Organization CognitiveAgent)')
        self.assertTrue(self.s.path('Human', 'AutonomousAgent')[0]['where'].startswith('Tiny.kif:'))


class FrameAndInferenceTests(unittest.TestCase):
    def setUp(self):
        self.sumo = sumo_from(KIF)
        self.lex = FakeLexicon({('assets', 'n'): [('assets.n.01', 'Entity')],
                                ('hold', 'v'): [('keep.v.01', 'Keeping')],
                                ('order', 'v'): [('order.v.01', 'Process')],
                                ('signature', 'n'): [('signature.n.01', 'Attribute')],
                                ('report', 'n'): [('report.n.01', 'Proposition')],
                                ('name', 'n'): [('name.n.01', 'Proposition')]})
        self.typer = Typer(self.sumo, self.lex)

    def run_frames(self, text, words, parties=()):
        fs = fr.frames(sentence(text, words), text)
        ctx = Context(fs, Definitions())
        for name in parties:  # as the instrument's own alias definition would register it
            ctx.names[name.lower()] = {'name': name, 'class': 'Organization', 'via': 'alias'}
        return fs, [infer_frame(f, self.typer, ctx) for f in fs]

    def test_organization_cannot_hold_an_obligation(self):
        fs, infs = self.run_frames(HOLD, HOLD_WORDS, parties=['Coinbase'])
        modal = next(i for f, i in zip(fs, infs) if f['rule'] == 'modal-action')
        self.assertEqual(modal['reading'], 'commitment')
        bearer = next(c for c in modal['checks'] if c['role'] == 'bearer')
        self.assertEqual((bearer['check']['best']['term'], bearer['check']['status']), ('Organization', 'not-entailed'))
        event = next(i for f, i in zip(fs, infs) if f['rule'] == 'property-event')
        self.assertIn('custody/bailment', [fd['key'] for fd in event['findings']])
        self.assertTrue(any(fd['kind'] == 'lexical-gap' and fd['key'] == 'digital_asset' for fd in event['findings']))

    def test_grant_reads_object_control(self):
        fs, infs = self.run_frames(AUTH, AUTH_WORDS)
        grant = next((f, i) for f, i in zip(fs, infs) if f['rule'] == 'grant')
        self.assertEqual(grant[0]['objects'][0]['quote'], 'Contoso')
        self.assertTrue(grant[0]['complement']['full'].startswith('to order'))
        self.assertEqual(grant[1]['checks'][0]['check']['status'], 'not-entailed')
        order = next(f for f in fs if f['rule'] == 'property-event')
        self.assertEqual([a['quote'] for a in order['agent']], ['Contoso'])

    def test_will_of_a_non_party_is_a_prediction(self):
        fs, infs = self.run_frames(HOLD, HOLD_WORDS)
        modal = next(i for f, i in zip(fs, infs) if f['rule'] == 'modal-action')
        self.assertEqual((modal['reading'], modal['readingRule']), ('prediction', 'non-party-subject'))
        self.assertFalse(any(fd['kind'] == 'bearer-type' for fd in modal['findings']))

    def test_must_of_a_thing_is_a_requirement(self):
        fs, infs = self.run_frames(MATCH, MATCH_WORDS)
        modal = next(i for f, i in zip(fs, infs) if f['rule'] == 'modal-action')
        self.assertEqual(modal['reading'], 'requirement')
        self.assertTrue(modal['formula'].startswith('(modalAttribute (match <The name>'))

    def test_overlay_term_format_and_retraction(self):
        s = sumo_from(KIF)
        s.extend('(subclass DigitalAsset Object) (termFormat EnglishLanguage DigitalAsset "digital asset")', 'P.kif')
        typing = Typer(s, self.lex).type(fr.Sentence(sentence(HOLD, HOLD_WORDS), HOLD).node(6), Context([], Definitions()))
        self.assertEqual(typing['selected']['term'], 'DigitalAsset')
        self.assertIsNotNone(s.incoherent('FinancialAccount'))
        self.assertTrue(s.retract('subclass FinancialAccount Account'))
        self.assertIsNone(s.incoherent('FinancialAccount'))

    def test_semicolon_of_series_is_one_list(self):
        sent = fr.Sentence(sentence(SERIES, SERIES_WORDS), SERIES)
        self.assertEqual([sent.w[i]['text'] for i in sent.of_series(3)], ['things', 'animals', 'obligations'])

    def test_negative_inversion_is_repaired(self):
        sent = fr.Sentence(sentence(NOEVENT, NOEVENT_WORDS), NOEVENT)
        self.assertEqual(sent.repairs[0]['rule'], 'negative-inversion')
        self.assertEqual((sent.rel(5), sent.w[5]['head'], sent.w[4]['head'], sent.rel(8)), ('nsubj', 10, 10, 'conj'))
        self.assertTrue(sent.negated(10))
        self.assertEqual(NOEVENT_WORDS[4]['deprel'], 'advcl')  # the cached parse record is not touched

    def test_non_agent_subject_is_constitutive(self):
        fs, infs = self.run_frames(EFFECT, EFFECT_WORDS)
        modal = next(i for f, i in zip(fs, infs) if f['rule'] == 'modal-action')
        self.assertEqual(modal['reading'], 'constitutive')


if __name__ == '__main__':
    unittest.main()
