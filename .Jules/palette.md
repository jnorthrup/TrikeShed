## 2024-05-15 - [Keyboard Accessibility for Actions]
**Learning:** In script.js, 'a' tags without 'href' were being used for JS click actions in the mountCta function, making them inaccessible to keyboard users because they lack default focusability and keyboard event handlers.
**Action:** Use 'button' elements for custom JS actions instead of 'a' tags without hrefs, ensuring default keyboard accessibility.
## 2024-05-15 - [Keyboard Accessibility Focus Rings]
**Learning:** Standard `:focus` outlines on `tabindex="0"` elements like table cells cause jarring visual rings during standard mouse clicks.
**Action:** Use `:focus-visible` for keyboard navigation styles and explicitly set `outline: none;` on `:focus` to suppress the browser default ring for mouse interactions.
