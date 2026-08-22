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

## 2025-01-22 - Reducing screen reader noise for decorative elements
**Learning:** Dynamically created DOM elements used purely for visual styling or as decorative markers (such as tree toggles '▾' / '▸', tree icons '▤', bullet markers '•', or slash menu icons) can create significant noise for screen reader users if left unannotated. Screen readers may read out the literal characters (e.g., "black right-pointing small triangle") which interrupts the flow and doesn't add semantic value when the adjacent text already describes the item.
**Action:** When dynamically generating decorative icon or marker elements via JavaScript (e.g., `document.createElement('span')`), explicitly set `aria-hidden="true"` via `setAttribute('aria-hidden', 'true')` to silence them for assistive technologies, allowing the screen reader to focus on the meaningful sibling content.
## 2024-08-17 - Context for identical buttons
**Learning:** Identical buttons like "+ New" across multiple columns in a kanban board lack context for screen reader users. The screen reader would just say "button, + New" multiple times, without indicating which column it belongs to.
**Action:** When adding identical action buttons to lists or columns, always add an `aria-label` that includes the parent context, e.g. `aria-label="Add new card to ' + col.name"`.
## 2025-01-22 - Contextual ARIA labels for grouped dynamic buttons
**Learning:** Buttons created dynamically within grouped structures (like Kanban board columns) often have generic visible text like "+ New" or "Add". While visual users infer context from the surrounding column or list grouping, screen reader users exploring by tab order or elements list lose this visual context, hearing only "Add, button".
**Action:** When creating interactive elements inside visual groupings, dynamically generate a contextual `aria-label` that includes the grouping's name (e.g., `addBtn.setAttribute('aria-label', 'Add new card to ' + col.name);`) to restore context for assistive technologies.
## 2025-01-22 - Contextual ARIA labels for interactive Kanban cards
**Learning:** Kanban cards that double as buttons (e.g., clicking moves them to the next column) are disorienting for screen reader users if they lack context. A screen reader will just read the card's text, without indicating its current status/column or what action activating it performs.
**Action:** When creating interactive items like Kanban cards, dynamically generate an `aria-label` that includes the card's name, its current column, and the action triggered upon interaction (e.g., "Move card 'Task' from 'To Do' to 'In Progress'").

## 2026-08-20 - Global keyboard handlers for custom buttons
**Learning:** When using custom DOM elements (like \`div\` or \`span\`) as interactive buttons by adding \`role="button"\` and \`tabindex="0"\`, they do not natively respond to \`Enter\` or \`Space\` keys like standard \`<button>\` elements do. Adding individual \`keydown\` listeners to every custom button creates redundant code, risks inconsistency, and is easy to miss on new components.
**Action:** Implement a global event delegation listener on the \`document\` for the \`keydown\` event. When \`Enter\` or \`Space\` is pressed, check if the \`e.target.getAttribute('role') === 'button'\`, and if so, call \`e.target.click()\`. This ensures all current and future custom buttons are automatically keyboard accessible without duplicate logic.

## 2025-01-22 - Adding focus-visible styles to custom interactive elements
**Learning:** Elements that use `role="button"` and `tabindex="0"` for custom interactivity (such as a drop zone or custom toggles) often lack native focus states. If their focus-visible styles are not explicitly defined in CSS, keyboard users will not know when these elements receive focus, violating accessibility guidelines.
**Action:** When making custom elements interactive by adding `tabindex="0"`, ensure that a `:focus-visible` CSS rule (e.g., `outline: 2px solid var(--accent);`) is applied to them to provide clear visual feedback during keyboard navigation.
