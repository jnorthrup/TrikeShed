## 2024-05-15 - [Keyboard Accessibility for Actions]
**Learning:** In script.js, 'a' tags without 'href' were being used for JS click actions in the mountCta function, making them inaccessible to keyboard users because they lack default focusability and keyboard event handlers.
**Action:** Use 'button' elements for custom JS actions instead of 'a' tags without hrefs, ensuring default keyboard accessibility.
## 2026-09-16 - Replace anchor tags without hrefs with buttons
**Learning:** Using `<a>` tags without `href` attributes for JS actions prevents default keyboard focusability, harming accessibility.
**Action:** Always use `<button type="button">` for custom JavaScript click handlers to ensure keyboard accessibility (tabbing).
