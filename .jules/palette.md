## 2024-10-24 - Remove redundant aria-labels from buttons
**Learning:** Adding aria-label to buttons that already have clear, visible text is an accessibility anti-pattern. Screen readers may read the aria-label instead of or in addition to the visible text, causing confusion.
**Action:** Only use aria-labels for icon-only buttons or when the visible text is insufficient on its own. Check existing text before adding aria-labels.
