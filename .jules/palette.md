## 2026-09-23 - Focus Ring Visibility for Accessibility
**Learning:** In HTML/JS UIs, styling `:focus { outline: none; }` creates a CSS cascade bug overriding `:focus-visible` due to equal specificity. This completely hides focus outlines for keyboard users if `:focus` appears later or takes precedence.
**Action:** When suppressing default browser outlines for mouse interactions on focusable elements (e.g. `tabindex="0"`), always use `:focus:not(:focus-visible) { outline: none; }` to ensure keyboard navigation remains accessible while preventing jarring outlines for mouse users.
