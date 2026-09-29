## 2024-05-18 - Added focus-visible states to futon.html
**Learning:** Custom standalone HTML pages in this repo (like futon.html and vm-terminal.html) often omit core accessibility focus styles but have distinct, explicitly defined color schemes.
**Action:** When modifying them, ensure `:focus-visible` styling is included (using existing CSS variables for color) to improve keyboard accessibility without duplicating custom CSS blocks.
