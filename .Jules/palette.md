## 2025-01-20 - Palette Journal Initialization
**Learning:** Initializing the journal for tracking critical UX and accessibility learnings.
**Action:** Use this file to record specific insights related to accessibility and UX patterns in the TrikeShed app.

## 2025-01-20 - Adding ARIA attributes to placeholders and contenteditable regions
**Learning:** Inputs that only use the `placeholder` attribute for context (like a search bar or a column filter input) lack a reliable accessible name for screen readers, as the placeholder often disappears during typing or isn't spoken properly. Additionally, `contenteditable` elements, acting essentially as rich-text textareas, are opaque to screen readers if they lack an explicit label or `aria-label`.
**Action:** Ensure that standalone inputs (especially search or filter fields without explicit `<label>` elements) always carry an `aria-label`. For interactive editor components like `contenteditable` divs, treat them as input regions and explicitly annotate them with an `aria-label` (e.g., `aria-label="Block content"`) to provide necessary context for screen reader users.

## 2025-01-20 - Adding ARIA attributes to empty contenteditable headings
**Learning:** Heading elements (like `<h1>`) that have `contenteditable="true"` but rely entirely on CSS pseudo-elements or data attributes (like `data-placeholder="Untitled"`) for empty states are completely invisible to screen readers' context. A screen reader will land on the element and report "heading level 1" or blank, omitting the crucial context of what text field the user is actually editing.
**Action:** Always provide an explicit `aria-label` (e.g., `aria-label="Document title"`) on empty, editable heading or rich-text elements to ensure assistive technologies can describe the input intent accurately.

## 2025-01-20 - WCAG 2.5.3 Label in Name rule
**Learning:** When adding `aria-label` for accessibility, it overrides the accessible name of an element. If an `aria-label` is added to a button that contains visible text, it can violate WCAG 2.5.3 (Label in Name) if the `aria-label` doesn't contain the visible text. This breaks voice dictation software for users.
**Action:** Never add `aria-label` attributes to buttons that already contain visible, descriptive text (like 'Share' or 'New Doc'). Reserve `aria-label` for icon-only buttons, standalone inputs without labels, or generic interactive regions like `contenteditable` tags.

## 2025-01-20 - Hiding decorative icons with aria-hidden
**Learning:** When using Unicode symbols (like ⌕, ⌂, ▦, ⬇, ▤) purely for visual decoration next to descriptive text, screen readers will try to announce the symbol. This creates unnecessary auditory noise and degrades the user experience.
**Action:** Always apply `aria-hidden="true"` to decorative elements, especially Unicode symbols or SVGs, when they are paired with actual accessible text or contained within a parent element that already provides an adequate `aria-label`.
