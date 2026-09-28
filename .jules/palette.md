## 2024-10-24 - Accessibility focus styles in standalone pages
**Learning:** Standalone HTML pages (e.g., vm-terminal.html, hermes-xterm.html) in this project often omit the core accessibility focus styles found in the main application CSS, leading to poor keyboard navigation visibility.
**Action:** Always check for and add explicit `:focus-visible` styling (while suppressing default `:focus` for mouse clicks) to interactive elements when modifying or creating standalone custom HTML pages.
