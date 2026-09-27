## 2024-05-24 - Add focus-visible states for keyboard navigation
**Learning:** Custom standalone HTML pages (like vm-terminal.html) in this app often omit the core accessibility focus styles found in the main application CSS, leaving keyboard navigation invisible.
**Action:** Always check custom HTML pages that declare their own `<style>` blocks for missing `:focus-visible` styling on interactive elements (`a`, `button`, `input`, `[tabindex="0"]`).
