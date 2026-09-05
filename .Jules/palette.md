## 2025-01-20 - Palette Journal Initialization
**Learning:** Initializing the journal for tracking critical UX and accessibility learnings.
**Action:** Use this file to record specific insights related to accessibility and UX patterns in the TrikeShed app.

## 2025-01-20 - Adding ARIA attributes to placeholders and contenteditable regions
**Learning:** Inputs that only use the `placeholder` attribute for context (like a search bar or a column filter input) lack a reliable accessible name for screen readers, as the placeholder often disappears during typing or isn't spoken properly. Additionally, `contenteditable` elements, acting essentially as rich-text textareas, are opaque to screen readers if they lack an explicit label or `aria-label`.
**Action:** Ensure that standalone inputs (especially search or filter fields without explicit `<label>` elements) always carry an `aria-label`. For interactive editor components like `contenteditable` divs, treat them as input regions and explicitly annotate them with an `aria-label` (e.g., `aria-label="Block content"`) to provide necessary context for screen reader users.

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
## 2026-08-20 - Global keyboard handlers for custom buttons
**Learning:** When using custom DOM elements (like \`div\` or \`span\`) as interactive buttons by adding \`role="button"\` and \`tabindex="0"\`, they do not natively respond to \`Enter\` or \`Space\` keys like standard \`<button>\` elements do. Adding individual \`keydown\` listeners to every custom button creates redundant code, risks inconsistency, and is easy to miss on new components.
**Action:** Implement a global event delegation listener on the \`document\` for the \`keydown\` event. When \`Enter\` or \`Space\` is pressed, check if the \`e.target.getAttribute('role') === 'button'\`, and if so, call \`e.target.click()\`. This ensures all current and future custom buttons are automatically keyboard accessible without duplicate logic.

## 2025-10-24 - Adding aria-selected to dynamic tab elements
**Learning:** When dynamically generating components with `role="tab"`, adding an `active` CSS class is not enough for screen readers. Without the `aria-selected` attribute, screen reader users cannot tell which tab in the `tablist` is currently active.
**Action:** Always pair visual active states (like `.active` classes) on `role="tab"` elements with `aria-selected="true"` or `aria-selected="false"` to ensure the active state is programmatically announced by assistive technologies.
## 2024-08-20 - Adding focus states for drag and drop drop zones
**Learning:** For elements handling drag-and-drop file ingestions natively created as semantic `role="button"` placeholders like `.drop-zone` in index.html templates, ensuring they receive `:focus-visible` styling is crucial for keyboard users attempting to access upload functions.
**Action:** Always add interactive form and UI upload containers defined with tabindex to the globally applied `:focus-visible` CSS selector lists.
## 2026-08-23 - Keyboard accessibility for pseudo-buttons
**Learning:** Elements using `role="button"` and `tabindex="0"` do not natively fire click events on `Enter` or `Space` like standard `<button>` elements do.
**Action:** Always add a generic or specific `keydown` event listener to these elements to translate `Enter` and `Space` key presses into `.click()` calls.

## 2025-01-22 - Adding context to interactive board cards
**Learning:** Kanban board cards acting as buttons (`role="button"`) without an `aria-label` only announce their visible contents (title and meta text) when navigated via a screen reader. This leaves the user without any hint that the card is interactive, what action activating it performs (e.g., cycling to the next column), or which column the card currently resides in when exploring via a flat element list.
**Action:** When custom components like board cards act as interactive buttons, explicitly provide an `aria-label` that includes the card's name, its current contextual state (e.g., the column it is in), and the action that will occur upon activation (e.g., `card.title + ' (in ' + col.name + ') - activate to move to next column'`).

## 2024-05-24 - Add interactive and focus styles to unstyled drop zone
**Learning:** The drop zone for the file ingest feature (`#drop-zone`) lacked CSS styling and interactive handlers, making it appear as unstyled inline text without any keyboard interactivity. This is a common pattern where newer features in the app shell get added to the HTML but corresponding CSS/JS are missed.
**Action:** Added proper styles (`.drop-zone`, `:hover`, `:focus-visible`) and wired up a click/keydown event listener in `script.js` to correctly forward interactions to the hidden file input. In the future, verify that new features using `aria-label` and `role="button"` also have corresponding keyboard handlers (`Enter`/`Space`) and visible focus states.
## 2024-05-19 - Keyboard Accessibility for Interactive Elements
**Learning:** UI elements designated as buttons via `role="button"` and `tabindex="0"` (like `#drop-zone`) must explicitly handle keyboard events (`Enter` and `Space`) in JavaScript to be fully accessible, especially when wrapping native inputs like file uploaders. Without this, keyboard-only or screen reader users cannot activate the element.
**Action:** Always attach both `click` and `keydown` listeners to custom interactive elements that do not use native `<button>` tags.
## 2025-01-22 - Adding focus-visible styles to custom interactive elements
**Learning:** Elements that use `role="button"` and `tabindex="0"` for custom interactivity (such as a drop zone or custom toggles) often lack native focus states. If their focus-visible styles are not explicitly defined in CSS, keyboard users will not know when these elements receive focus, violating accessibility guidelines.
**Action:** When making custom elements interactive by adding `tabindex="0"`, ensure that a `:focus-visible` CSS rule (e.g., `outline: 2px solid var(--accent);`) is applied to them to provide clear visual feedback during keyboard navigation.

## 2025-02-23 - Announcing active states for single-page application navigation
**Learning:** Single-page applications often use custom buttons to switch views instead of actual `<a>` tags with `href`s. While visual users see an active state (like a background color change), screen reader users hear no change in state unless explicitly announced.
**Action:** When building custom view switchers (like tabs or navigation sidebar items) that aren't native links, always apply `aria-current="true"` (or `aria-current="page"` for navigation menus) or `aria-pressed="true"` (for toggle buttons) via JavaScript when the view changes.
<<<<<<< ours
## 2024-05-23 - Context-Specific ARIA Labels for Kanban Cards
 **Learning:** Screen readers lose visual grouping context on dynamically created interactive components like Kanban cards, leading to ambiguity for users navigating via keyboard.
 **Action:** Always add context-specific aria-labels that explicitly include the column/list name and explain the interaction that will happen upon activation.
=======
=======
## 2024-08-20 - Adding focus states for drag and drop drop zones
**Learning:** For elements handling drag-and-drop file ingestions natively created as semantic `role="button"` placeholders like `.drop-zone` in index.html templates, ensuring they receive `:focus-visible` styling is crucial for keyboard users attempting to access upload functions.
**Action:** Always add interactive form and UI upload containers defined with tabindex to the globally applied `:focus-visible` CSS selector lists.
>>>>>>> theirs
## 2025-02-23 - Announcing active states for single-page application navigation
**Learning:** Single-page applications often use custom buttons to switch views instead of actual `<a>` tags with `href`s. While visual users see an active state (like a background color change), screen reader users hear no change in state unless explicitly announced.
**Action:** When building custom view switchers (like tabs or navigation sidebar items) that aren't native links, always apply `aria-current="page"` via JavaScript when the view changes.
## 2024-10-27 - Added aria-labels to main toolbar buttons
**Learning:** In dynamically toggled views and fixed main toolbars, ensure text or icon buttons have explicitly descriptive `aria-label` attributes.
**Action:** Use standard `aria-label` attributes consistently for all interactive elements in custom toolbars.
<<<<<<< ours
>>>>>>> theirs
=======
>>>>>>> theirs
## 2024-08-24 - Dynamic ARIA Label Injection
**Learning:** When dynamically rendering interactive lists in vanilla JS (like page trees, sheet references, or slash menus), screen reader context is lost if we only use CSS classes like `.active` to indicate state.
**Action:** When creating elements with `document.createElement`, proactively attach explicit, descriptive `aria-label`s that encapsulate both the item's identity and its current state (e.g., `"Active page: Untitled"` or `"Navigate to parent sheet: ..."`).
<<<<<<< HEAD
<<<<<<< HEAD
<<<<<<< HEAD
<<<<<<< HEAD
<<<<<<< HEAD
## 2025-05-24 - Synchronizing active states for duplicate navigation items
**Learning:** When navigation buttons exist in multiple places (e.g., a topbar and a sidebar), visual styling and ARIA attributes (like `aria-current="page"`) must be updated on all instances when the view changes. Screen reader users navigating the sidebar would otherwise not know which view is currently active.
**Action:** Expand view-switching logic to query and update all relevant navigation buttons, not just the primary ones, ensuring consistent state across the entire UI.
=======
## 2026-08-27 - WCAG 2.5.3 Label in Name rule\n**Learning:** When using `aria-label` on elements that already contain visible text, screen readers override the visible text with the ARIA label. If they do not match exactly, it causes a WCAG 2.5.3 'Label in Name' violation, which confuses speech-input users who try to voice the visible text.\n**Action:** Use `title` attributes on buttons with visible text instead of `aria-label` to provide supplementary tooltips without overriding the accessible name.
>>>>>>> origin/palette-fix-wcag-253-label-in-name-4042681561026698692
=======
## 2024-05-25 - Syncing active states for duplicate navigation items
**Learning:** When a single-page application has duplicate navigation buttons (e.g., both topbar and sidebar buttons for the same views), updating the active state (`aria-current="page"` and visual classes) on only one set of buttons leaves the other set in an ambiguous or incorrect state, confusing screen reader users navigating the DOM.
**Action:** Ensure that view-switching logic globally queries and updates all duplicate instances of navigation buttons for the active view to maintain consistent `aria-current="page"` attributes and visual active states.
>>>>>>> origin/palette-sidebar-active-states-17480957366909985694
=======
## 2025-02-23 - Active states for duplicate navigation items
**Learning:** When managing view state in a single-page application (like TrikeShed's frontend), ensure that all duplicate instances of navigation buttons for the active view (e.g., both topbar and sidebar buttons) consistently receive the `aria-current="page"` attribute and visual active state updates to prevent ambiguous states for screen reader users.
**Action:** Synchronize the `active` class and `aria-current="page"` state for all duplicate navigation buttons across the interface when the active view changes.
>>>>>>> origin/palette-sidebar-active-states-18252850510239450495
=======
## 2025-02-23 - Keeping duplicate navigation controls in sync
**Learning:** In a UI layout with redundant navigation controls (like a topbar and a sidebar that both control the active view), only applying `.active` and `aria-current="page"` to the primary control (e.g. topbar) leaves the secondary control in an ambiguous state. A screen reader user navigating the sidebar would hear that none of the sidebar items are the current page.
**Action:** When updating the active state of navigation links/buttons, ensure that all duplicate instances representing the same destination are synchronized with the visual active class and `aria-current="page"`.
>>>>>>> origin/palette-sidebar-active-sync-363112185002837110
=======
## 2024-10-27 - Consistency across duplicate navigation items
**Learning:** When managing view state in a single-page application, navigation buttons are sometimes duplicated (e.g., in a sidebar and a topbar). If active visual classes and accessibility attributes (like `aria-current="page"`) are only applied to one set of buttons, it creates an ambiguous and inconsistent state for screen reader users and sighted users relying on the secondary navigation.
**Action:** Ensure that all duplicate instances of navigation buttons for the active view consistently receive the `aria-current="page"` attribute and visual active state updates to prevent ambiguous states.
>>>>>>> origin/palette-sidebar-navigation-active-state-4631464799665027047
