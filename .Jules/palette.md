<<<<<<< HEAD
## 2025-01-20 - Adding ARIA attributes to generic HTML Builder inputs**Action:** When auditing custom UI DSLs or HTML builder utilities, verify that accessibility attributes are exposed as top-level arguments, not just classes or IDs. Update upstream builder functions to accept and properly render these attributes.**Learning:** When creating general-purpose HTML builder functions (like `HtmlBuilder.input()` in TrikeShed), it's crucial to include explicit parameter support for accessibility attributes like `aria-label`. Without this generic support, downstream property editors (Text, Number, Date, Url, Email, Phone) that rely on these builders will implicitly fail to provide accessible names, causing widespread a11y gaps across dynamic forms.
=======
## 2024-08-04 - Palette Journal Initialization
**Learning:** Initializing the journal for tracking critical UX and accessibility learnings.
**Action:** Use this file to record specific insights related to accessibility and UX patterns in the TrikeShed app.

## 2025-01-20 - Adding ARIA attributes to generated HTML in Kotlin React-like DSLs
**Learning:** When using Kotlin string-builder-based DSLs (like `text("<button...")`) to generate HTML elements, ARIA attributes and titles need to be explicitly added as escaped strings (e.g., `aria-label="My Label"`). Icon-only buttons used in inline editors (like BlockEditor and DatabaseView) often lack these attributes by default because they are generated programmatically for brevity.
**Action:** Always check programmatic HTML generation for missing accessibility attributes, especially for UI controls represented only by symbols (↑, ↓, +, x, ✎).

## 2025-01-20 - Adding ARIA attributes to generic HTML Builder inputs
**Learning:** When creating general-purpose HTML builder functions (like `HtmlBuilder.input()` in TrikeShed), it's crucial to include explicit parameter support for accessibility attributes like `aria-label`. Without this generic support, downstream property editors (Text, Number, Date, Url, Email, Phone) that rely on these builders will implicitly fail to provide accessible names, causing widespread a11y gaps across dynamic forms.
**Action:** When auditing custom UI DSLs or HTML builder utilities, verify that accessibility attributes are exposed as top-level arguments, not just classes or IDs. Update upstream builder functions to accept and properly render these attributes.
>>>>>>> origin/palette-aria-labels-5089432666783558495
