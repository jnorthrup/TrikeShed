"""SUMO (SUO-KIF) taxonomy and the WordNet 3.0 to SUMO lexicon, as plain indexes.

Reads the pinned ontologyportal/sumo corpus TrikeShed already fetches (`fetchSumoFullCorpus`,
ref in utils/sumo/sumo-full-corpus.pins) and the WordNetMappings30 files. Only the taxonomy
forms are indexed (subclass, instance, subrelation, subAttribute, domain, range, disjointness,
documentation); rules are counted, not run. Every indexed fact keeps file:line provenance, and
commented-out taxonomy axioms are kept too, since SUMO records some deliberate omissions that way.
"""
from __future__ import annotations

import glob
import hashlib
import os
import pickle
import re
from collections import defaultdict
from functools import lru_cache

TOKEN = re.compile(r'"(?:[^"\\]|\\.)*"|;[^\n]*|[()]|[^\s()";]+', re.S)
COMMENTED = re.compile(r'\((subclass|instance|subrelation|domain|range|disjoint|partition)\s+([^\s()]+)\s+([^()]+?)\)')
INDEX_VERSION = 4


def read_forms(text: str):
    stack, forms, comments = [], [], []
    for m in TOKEN.finditer(text):
        t = m.group()
        if t[0] == ';':
            comments.append((m.start(), t))
        elif t == '(':
            stack.append(([], m.start()))
        elif t == ')':
            if stack:
                lst, start = stack.pop()
                (stack[-1][0].append(lst) if stack else forms.append((start, lst)))
        elif stack:
            stack[-1][0].append(t)
    return forms, comments


class Sumo:
    def __init__(self, files: list[str], root: str):
        self.files = [os.path.relpath(f, root) for f in files]
        self.parents = defaultdict(set)      # class -> superclasses
        self.classes_of = defaultdict(set)   # individual -> classes
        self.subrel = defaultdict(set)
        self.subattr = defaultdict(set)
        self.domain = defaultdict(set)       # (relation, n) -> classes
        self.domain_subclass = defaultdict(set)
        self.range = defaultdict(set)
        self.disjoint = {}                   # frozenset({a, b}) -> where
        self.doc = {}
        self.term_format = defaultdict(set)  # lower-cased English phrase -> terms (termFormat EnglishLanguage)
        self.where = {}                      # (kind, a, b) -> file:line
        self.commented = []                  # (where, text)
        self.rules = 0
        self.known = set()
        for path, rel in zip(files, self.files):
            with open(path, encoding='utf-8', errors='replace') as f:
                text = f.read()
            lines = [0] + [m.end() for m in re.finditer('\n', text)]
            import bisect

            def at(offset):
                return f'{rel}:{bisect.bisect_right(lines, offset)}'
            forms, comments = read_forms(text)
            for start, c in comments:
                for m in COMMENTED.finditer(c):
                    self.commented.append((at(start), m.group(0)))
            for start, form in forms:
                self._index(form, at(start))

    def _index(self, form, where):
        if not form or not isinstance(form[0], str):
            return
        head = form[0]
        args = form[1:]
        atoms = [a for a in args if isinstance(a, str)]
        if head in ('=>', '<=>', 'and', 'or', 'exists', 'forall', 'not'):
            self.rules += 1
            return
        if head == 'subclass' and len(atoms) == 2:
            self.parents[atoms[0]].add(atoms[1]); self.known.update(atoms)
            self.where.setdefault(('subclass', atoms[0], atoms[1]), where)
        elif head == 'instance' and len(atoms) == 2:
            self.classes_of[atoms[0]].add(atoms[1]); self.known.update(atoms)
            self.where.setdefault(('instance', atoms[0], atoms[1]), where)
        elif head == 'subrelation' and len(atoms) == 2:
            self.subrel[atoms[0]].add(atoms[1]); self.known.update(atoms)
        elif head == 'subAttribute' and len(atoms) == 2:
            self.subattr[atoms[0]].add(atoms[1]); self.known.update(atoms)
        elif head in ('domain', 'domainSubclass') and len(atoms) == 3:
            target = self.domain if head == 'domain' else self.domain_subclass
            target[(atoms[0], int(atoms[1]))].add(atoms[2])
            self.where.setdefault((head, atoms[0], atoms[1], atoms[2]), where)
        elif head == 'range' and len(atoms) == 2:
            self.range[atoms[0]].add(atoms[1])
        elif head == 'disjoint' and len(atoms) == 2:
            self.disjoint.setdefault(frozenset(atoms), where)
        elif head in ('partition', 'disjointDecomposition') and len(atoms) >= 3:
            parts = atoms[1:]
            for i, a in enumerate(parts):
                for b in parts[i + 1:]:
                    self.disjoint.setdefault(frozenset((a, b)), where)
        elif head == 'termFormat' and len(args) >= 3 and args[0] == 'EnglishLanguage' and isinstance(args[2], str):
            phrase = re.sub(r'\s+', ' ', args[2].strip('"').lower()).strip()
            if phrase and isinstance(args[1], str):
                self.term_format[phrase].add(args[1])
                self.where.setdefault(('termFormat', args[1], phrase), where)
        elif head == 'documentation' and len(args) >= 3 and args[1] == 'EnglishLanguage':
            self.doc.setdefault(args[0], (args[2].strip('"'), where))
            self.known.add(args[0])

    # -- queries ---------------------------------------------------------------------------
    @lru_cache(maxsize=None)
    def ancestors(self, term: str) -> frozenset:
        seen, stack = set(), list(self.parents.get(term, ())) + list(self.classes_of.get(term, ()))
        while stack:
            t = stack.pop()
            if t not in seen:
                seen.add(t)
                stack.extend(self.parents.get(t, ()))
        return frozenset(seen)

    def isa(self, term: str, cls: str) -> bool:
        return term == cls or cls in self.ancestors(term)

    def disjoint_witness(self, a: str, b: str):
        left = self.ancestors(a) | {a}
        right = self.ancestors(b) | {b}
        for x in left:
            for y in right:
                w = self.disjoint.get(frozenset((x, y)))
                if w and x != y:
                    return {'between': [x, y], 'where': w}
        return None

    def is_instance_term(self, term: str) -> bool:
        return bool(self.classes_of.get(term)) and not self.parents.get(term)

    def incoherent(self, term: str):
        """A disjoint pair among the term's own ancestors: no instance of it can exist."""
        anc = sorted(self.ancestors(term) | {term})
        for i, x in enumerate(anc):
            for y in anc[i + 1:]:
                w = self.disjoint.get(frozenset((x, y)))
                if w:
                    return {'between': [x, y], 'where': w}
        return None

    def path(self, term: str, target: str):
        """One subclass/instance chain from term up to target, each edge with file:line."""
        from collections import deque
        prev: dict[str, str | None] = {term: None}
        q = deque([term])
        while q:
            t = q.popleft()
            if t == target:
                chain = []
                while (p := prev[t]) is not None:
                    kind = 'subclass' if t in self.parents.get(p, ()) else 'instance'
                    chain.append({'form': f'({kind} {p} {t})', 'where': self.where.get((kind, p, t))})
                    t = p
                return list(reversed(chain))
            for n in list(self.parents.get(t, ())) + list(self.classes_of.get(t, ())):
                if n not in prev:
                    prev[n] = t; q.append(n)
        return None

    def extend(self, text: str, name: str):
        """Index an overlay (proposed axioms) on top of the pinned corpus."""
        import bisect
        lines = [0] + [m.end() for m in re.finditer('\n', text)]
        forms, _ = read_forms(text)
        for start, form in forms:
            self._index(form, f'{name}:{bisect.bisect_right(lines, start)}')
        Sumo.ancestors.cache_clear()
        return len(forms)

    def retract(self, form: str) -> bool:
        """Drop one taxonomy edge, e.g. "subclass FinancialAccount Account", to test a corpus patch."""
        kind, a, b = form.split()
        table = self.parents if kind == 'subclass' else self.classes_of
        if b in table.get(a, ()):
            table[a].discard(b)
            Sumo.ancestors.cache_clear()
            return True
        return False

    def commented_about(self, *terms: str):
        return [(w, t) for w, t in self.commented if all(re.search(rf'\b{re.escape(x)}\b', t) for x in terms)]


def load_sumo(root: str, cache_dir: str) -> Sumo:
    files = sorted(set(glob.glob(os.path.join(root, '*.kif')) + glob.glob(os.path.join(root, '**', '*.kif'), recursive=True)))
    files = [f for f in files if '/Translations/' not in f]
    h = hashlib.sha256(str(INDEX_VERSION).encode())
    for f in files:
        h.update(f.encode()); h.update(open(f, 'rb').read())
    path = os.path.join(cache_dir, f'sumo-index.{h.hexdigest()[:20]}.pickle')
    if os.path.isfile(path):
        with open(path, 'rb') as fh:
            return pickle.load(fh)
    s = Sumo(files, root)
    s.ancestors.cache_clear()
    os.makedirs(cache_dir, exist_ok=True)
    with open(path, 'wb') as fh:
        pickle.dump(s, fh)
    return s


DATA_LINE = re.compile(r'^(\d{8}) \d\d ([nvasr]) ([0-9a-f]{2}) ')
MAPPING = re.compile(r'&%([A-Za-z0-9_\-]+)([=+@:\[\]])')
POSITIVE = {'=': 'equivalent', '+': 'subsumed', '@': 'instance'}


class Lexicon:
    """WordNet 3.0 synsets in sense order (NLTK's copy of WordNet) joined to SUMO's mappings."""

    def __init__(self, mapping_files: dict[str, str], nltk_dir: str):
        import nltk
        if nltk_dir not in nltk.data.path:
            nltk.data.path.insert(0, nltk_dir)
        from nltk.corpus import wordnet as wn
        self.wn = wn
        self.map = {}
        self.sources = {}
        for pos, path in mapping_files.items():
            self.sources[pos] = {'file': os.path.basename(path), 'sha256': hashlib.sha256(open(path, 'rb').read()).hexdigest()}
            with open(path, encoding='utf-8', errors='replace') as f:
                for line in f:
                    m = DATA_LINE.match(line)
                    if not m:
                        continue
                    gloss = line.split(' | ', 1)[1] if ' | ' in line else ''
                    self.map[(pos, m.group(1))] = [(t, s) for t, s in MAPPING.findall(gloss)]

    @lru_cache(maxsize=None)
    def senses(self, lemma: str, pos: str) -> tuple:
        key = lemma.strip().lower().replace(' ', '_')
        if not key:
            return ()
        out = []
        for rank, syn in enumerate(self.wn.synsets(key, pos=pos), 1):
            file_pos = 'a' if syn.pos() in ('a', 's') else syn.pos()
            maps = self.map.get((file_pos, f'{syn.offset():08d}'), [])
            out.append({'rank': rank, 'synset': syn.name(), 'offset': f'{syn.offset():08d}', 'gloss': syn.definition(),
                        'mappings': [{'term': t, 'relation': POSITIVE.get(s, f'negated:{s}')} for t, s in maps]})
        return tuple(out)

    def is_lemma(self, word: str) -> bool:
        if not hasattr(self, '_lemmas'):
            self._lemmas = set(self.wn.all_lemma_names())
        return word in self._lemmas

    def has_entry(self, lemma: str, pos: str = 'n') -> bool:
        return bool(self.wn.synsets(lemma.strip().lower().replace(' ', '_'), pos=pos))
