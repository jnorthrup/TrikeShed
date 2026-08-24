"""
braille — pure-Python window/tk approximation for the GraalPy no-native guest.

PyQt/PySide/tkinter are native and BLOCKED (hermes-native-module-banlist.txt) so an LLM
ships the gap to Jules. This module is the replacement: Unicode Braille U+2800 (2×4 dots per
cell), VT-native, zero native deps — the go-to for window/tk approximation.

Mirrors `borg.trikeshed.graal.subvm.BrailleUi` (commonMain) so host and guest speak the same
cells. Computronium demo used NiceGUI (still minimal, pyqt-free); braille replaces it with
VT-only rendering that works in any polyglot subVM and in oroboros.

No `import tkinter`, no `import PyQt5` — those remain blocked and fail-closed elsewhere.
"""
BRAILLE_BASE = 0x2800

def cell(dots: int) -> str:
    return chr(BRAILLE_BASE + (dots & 0xFF))

def cell2x4(dots) -> str:
    """dots: 4×2 bool matrix [[x0,y0..], ...] -> braille char."""
    bits = 0
    if dots[0][0]: bits |= 0x01
    if dots[1][0]: bits |= 0x02
    if dots[2][0]: bits |= 0x04
    if dots[0][1]: bits |= 0x08
    if dots[1][1]: bits |= 0x10
    if dots[2][1]: bits |= 0x20
    if dots[3][0]: bits |= 0x40
    if dots[3][1]: bits |= 0x80
    return cell(bits)

def window_frame(title: str, w_cells: int, h_cells: int, body=None) -> str:
    body = body or []
    lines = []
    lines.append("┌" + "─" * w_cells + "┐")
    lines.append("│" + title.ljust(w_cells)[:w_cells] + "│")
    lines.append("├" + "─" * w_cells + "┤")
    for i in range(h_cells):
        line = body[i] if i < len(body) else ""
        lines.append("│" + line.ljust(w_cells)[:w_cells] + "│")
    lines.append("└" + "─" * w_cells + "┘")
    return "\n".join(lines)

def canvas(pixels) -> str:
    """pixels: list[list[bool]] y×x -> braille cells (2×4 dots per char)."""
    h = len(pixels)
    w = len(pixels[0]) if h else 0
    cell_h = (h + 3) // 4
    cell_w = (w + 1) // 2
    out = []
    for cy in range(cell_h):
        row = []
        for cx in range(cell_w):
            dots = [[False, False] for _ in range(4)]
            for dy in range(4):
                for dx in range(2):
                    y = cy * 4 + dy
                    x = cx * 2 + dx
                    if y < h and x < w and pixels[y][x]:
                        dots[dy][dx] = True
            row.append(cell2x4(dots))
        out.append("".join(row))
    return "\n".join(out)

class Label:
    def __init__(self, text: str): self.text = text
    def render(self): return self.text

class Button:
    def __init__(self, label: str, focused: bool = False): self.label = label; self.focused = focused
    def render(self): return f"[▶ {self.label} ◀]" if self.focused else f"[ {self.label} ]"

class Frame:
    def __init__(self, title: str, children=None): self.title = title; self.children = children or []
    def render(self, w=40, h=8):
        body = []
        for c in self.children:
            if hasattr(c, "render"): body.append(c.render())
            else: body.append(str(c))
        return window_frame(self.title, w, h, body)

# tkinter/PyQt drop-in hint — import braille instead:
#   from hermes.braille import Frame, Label, Button, canvas, window_frame
# blocked modules remain blocked; this is the Jules replacement part.
