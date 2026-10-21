## 2023-10-07 - Avoid Redundant aria-labels on Form Controls
**Learning:** In custom standalone HTML pages within this project, form controls (`<input>`, `<select>`, `<textarea>`) are often already accessible via implicit HTML wrapping (e.g., `<label>Text <input></label>`). Adding an explicit `aria-label` attribute to these elements overrides the visible text for screen readers, breaking WCAG 2.5.3 (Label in Name) and severely hindering voice-dictation users.
**Action:** Before attempting to add `aria-label`s to form controls, verify if they are already wrapped in an implicit `<label>`. If they are, do NOT add `aria-label`. Instead, focus on adding missing `:focus-visible` styles to standard CSS files to improve keyboard navigation accessibility.

## 2026-03-30 - Focus-Visible Styling for Core App CSS
**Learning:** External CSS stylesheets for main UI applications (such as `mux.css`) often omit explicit `:focus-visible` ring indicators, relying on default browser focus rings that clash or fail color contrast requirements across custom themes.
**Action:** In custom CSS stylesheets like `mux.css`, inject explicit `:focus-visible` rules using the application's primary accent color variable (e.g., `var(--green)`) to ensure high contrast and consistent keyboard navigation accessibility across interactive elements.

## 2026-03-30 - Focus-Visible Styling for SpaceGraph Shadow CSS
**Learning:** Component-specific CSS stylesheets for shadow overlays (such as `spacegraph-shadow.css`) often omit `:focus-visible` rules for custom interactive controls (`.sg-command`, `.sg-tree-node`, `.sg-port`), resulting in poor keyboard focus indicators.
**Action:** In component CSS stylesheets, inject explicit `:focus-visible` ring indicators using the component's accent color (e.g. `#68c9a1`) targeting both standard controls and custom interactive elements.
