"""Source text to parse blocks without changing a character of the source.

Every offset here is a code-point index into the source text. `utf16_offsets` converts to the
half-open UTF-16 ranges TrikeShed's document spans use. A parse view replaces each newline inside
a block with one space, so view offsets equal source offsets; quotes are always cut from the
source, never rebuilt from tokens or lemmas.
"""
from __future__ import annotations

import re
from collections import Counter, defaultdict
from dataclasses import dataclass

TERMINAL = re.compile(r'[.!?]["”’)\]]*\s*$')
NUMBERED = re.compile(r'^(?P<num>\d+(?:\.\d+)*)\.?\s+(?P<title>[A-Z“"(].{0,110})$')
APPENDIX = re.compile(r'^(?P<num>APPENDIX\s+\d+)\s*:\s*(?P<title>.{1,110})$', re.I)
QUOTE_ONLY = {'"', '“', '”', '(“', '‘', '’', '(“', '("'}


@dataclass(frozen=True)
class Block:
    begin: int
    end: int
    section: str | None
    heading: bool


def utf16_offsets(text: str) -> list[int]:
    out = [0] * (len(text) + 1)
    u = 0
    for i, ch in enumerate(text):
        out[i] = u
        u += 2 if ord(ch) > 0xFFFF else 1
    out[len(text)] = u
    return out


def _heading(s: str):
    m = APPENDIX.match(s)
    if m:
        return m.group('num').upper(), m.group('title').strip()
    m = NUMBERED.match(s)
    if not m or len(s) > 110 or s.rstrip().endswith((',', ';')):
        return None
    title = m.group('title').strip()
    if len(title.split()) > 14 or title.count('. ') > 1:
        return None
    return m.group('num'), title.rstrip('.')


def _title_line(s: str) -> bool:
    words = s.split()
    return (1 <= len(words) <= 6 and not TERMINAL.search(s) and s[-1] not in ',;:'
            and all(w[0].isupper() or w.lower() in ('and', 'or', 'of', 'the', 'for', 'to', 'a', 'an', 'in', '&') for w in words))


def line_blocks(text: str, begin: int = 0, end: int | None = None) -> list[Block]:
    """Blocks for HTML-derived text, where inline markup broke sentences across lines.

    A line continues the open block unless the open block ends a sentence and the line starts a
    new one (capital, digit, opening quote or bracket). Numbered and appendix headings are their
    own blocks and name the section of the blocks after them; so is a short Title Case line that
    follows a finished sentence and precedes a capitalized line (a form or page title). Blank
    lines close a block.
    """
    end = len(text) if end is None else end
    blocks: list[Block] = []
    appendix: str | None = None
    section: str | None = None
    open_begin = open_end = None
    last_line = ''

    def close():
        nonlocal open_begin, open_end
        if open_begin is not None and open_end is not None:
            blocks.append(Block(open_begin, open_end, section, False))
        open_begin = open_end = None

    raws, pos = [], begin
    for raw in text[begin:end].split('\n'):
        raws.append((pos, raw))
        pos += len(raw) + 1
    for n, (b, raw) in enumerate(raws):
        s = raw.strip()
        if not s:
            close(); last_line = ''
            continue
        nxt = next((r.strip() for _, r in raws[n + 1:] if r.strip()), '')
        h = _heading(s)
        title = (h is None and _title_line(s) and (open_begin is None or TERMINAL.search(last_line))
                 and nxt[:1].isupper())
        if h or title:
            close()
            if h:
                num, ttl = h
                if num.startswith('APPENDIX'):
                    appendix, section = f'{num}: {ttl}', f'{num}: {ttl}'
                else:
                    section = f'{appendix} § {num} {ttl}' if appendix else f'§ {num} {ttl}'
            blocks.append(Block(b + (len(raw) - len(raw.lstrip())), b + len(raw.rstrip()), section, True))
            last_line = ''
            continue
        lead = b + (len(raw) - len(raw.lstrip()))
        trail = b + len(raw.rstrip())
        starts_sentence = s[0].isupper() or s[0].isdigit() or s[0] in '“"(‘'
        if open_begin is None or (TERMINAL.search(last_line) and starts_sentence and last_line.strip() not in QUOTE_ONLY):
            close()
            open_begin = lead
        open_end = trail
        last_line = s
    close()
    return blocks


def paragraph_blocks(text: str, begin: int = 0, end: int | None = None, section: str | None = None) -> list[Block]:
    """Blocks separated by blank lines (plain text, statutes, extracted PDF text)."""
    end = len(text) if end is None else end
    blocks = []
    for m in re.finditer(r'\S(?:.*?\S)?(?=\s*\n\s*\n|\s*\Z)', text[begin:end], re.S):
        blocks.append(Block(begin + m.start(), begin + m.end(), section, False))
    return blocks


VIEW_VERSION = 3  # 2: shouted blocks are truecased for the parser; 3: shouted sentences inside any block
WORD = re.compile(r"[^\W\d_]+(?:['’][^\W\d_]+)*")
SENTENCE_END = re.compile(r'[.!?:;]\s+$')
FUNCTION_WORDS = set('''a an the this that these those and or nor but if then than as at by for from in into of off on
onto out over to under up upon with within without about above after against among before below between
during through throughout until unto via i you he she it we they me him her us them my your his its our
their mine yours ours theirs who whom whose which what whether is are was were be been being am do does
did doing have has had having shall should will would may might must can could not no any all each every
some such other only own same so too very there here where when while because although though unless
including'''.split())


def shouting(seg: str) -> bool:
    """An all-capitals block (disclaimers, liability caps, arbitration notices)."""
    letters = [c for c in seg if c.isalpha()]
    return len(letters) >= 20 and sum(c.isupper() for c in letters) >= 0.8 * len(letters)


PIECE = re.compile(r'.+?(?:[.!?;:](?=\s)|$)', re.S)


def shouted_pieces(seg: str):
    """(begin, end) of each sentence-like piece of seg in capitals."""
    return [(m.start(), m.end()) for m in PIECE.finditer(seg) if shouting(m.group())]


def case_table(text: str, blocks: list[Block]) -> dict:
    """Most frequent casing of each word in the document's ordinary blocks, sentence-initial words
    excluded: the unigram truecaser. “COINBASE” reads as “Coinbase”, “ANY” as “any”, “AAA” stays."""
    counts = defaultdict(Counter)
    for blk in blocks:
        seg = text[blk.begin:blk.end]
        loud = shouted_pieces(seg)
        for m in WORD.finditer(seg):
            if any(b <= m.start() < e for b, e in loud):
                continue
            if m.start() == 0 or SENTENCE_END.search(seg[max(0, m.start() - 3):m.start()]):
                continue
            counts[m.group().lower()][m.group()] += 1
    return {low: c.most_common(1)[0][0] for low, c in counts.items()}


def truecase(seg: str, table: dict) -> str:
    """Same length, same letters: only case changes, so view offsets still map back to the source."""
    out = list(seg)
    for m in WORD.finditer(seg):
        w = m.group()
        form = table.get(w.lower())
        if w.lower() in FUNCTION_WORDS and all(len(c.lower()) == 1 for c in w):
            form = w.lower()
        elif form is None or len(form) != len(w):
            # unseen: a short one may be an acronym (“AAA”, “JAMS”); a long one is a shouted word
            form = w if (len(w) <= 4 or any(len(c.lower()) != 1 for c in w)) else w.lower()
        if m.start() == 0 or SENTENCE_END.search(seg[max(0, m.start() - 3):m.start()]):
            form = form[0].upper() + form[1:]
        out[m.start():m.end()] = form
    return ''.join(out)


def parse_view(text: str, blocks: list[Block]) -> tuple[str, list[tuple[int, Block]]]:
    """The string the parser reads: blocks joined by blank lines, inner newlines as spaces, shouted
    blocks truecased. Quotes are always cut from the source, never from this view."""
    parts, starts, pos = [], [], 0
    table = case_table(text, blocks) if any(shouted_pieces(text[b.begin:b.end]) for b in blocks) else {}
    for blk in blocks:
        seg = text[blk.begin:blk.end]
        seg = seg.translate({ord(c): ' ' for c in '\n\r\t\f\v\u2028\u2029'})
        for b, e in (shouted_pieces(seg) if table else ()):
            seg = seg[:b] + truecase(seg[b:e].lstrip(), table).rjust(e - b) + seg[e:]
        starts.append((pos, blk))
        parts.append(seg)
        pos += len(seg) + 2
    return '\n\n'.join(parts), starts


def view_to_source(starts: list[tuple[int, Block]]):
    import bisect
    keys = [p for p, _ in starts]

    def back(offset: int) -> int:
        i = bisect.bisect_right(keys, offset) - 1
        p, blk = starts[i]
        src = blk.begin + (offset - p)
        if not (blk.begin <= src <= blk.end):
            raise ValueError(f'view offset {offset} falls outside block {blk}')
        return src

    def block_of(offset: int) -> Block:
        return starts[bisect.bisect_right(keys, offset) - 1][1]

    return back, block_of
