## 2024-09-14 - Interactive elements should be keyboard accessible

**Learning:** Buttons should have a `focus-visible` state. If `focus` removes the outline, `focus-visible` should add an outline so keyboard users know which element has focus. However, adding `aria-label` to buttons that already contain visible, descriptive text is an accessibility anti-pattern because screen readers will read the `aria-label` instead of the visible text.

**Action:** Look for `outline: none;` on interactive elements and ensure `.class:focus-visible` defines an outline style. Do not add `aria-label` to buttons with descriptive text.
