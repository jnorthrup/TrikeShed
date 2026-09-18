## 2024-05-15 - [Keyboard Accessibility for Actions]
**Learning:** In script.js, 'a' tags without 'href' were being used for JS click actions in the mountCta function, making them inaccessible to keyboard users because they lack default focusability and keyboard event handlers.
**Action:** Use 'button' elements for custom JS actions instead of 'a' tags without hrefs, ensuring default keyboard accessibility.

## 2024-05-16 - [Keyboard Focus Styles on Mouse Click]
**Learning:** In table.sheet td elements, using the standard :focus pseudo-class for tabindex="0" items creates a jarring visual outline ring when users click the element with a mouse.
**Action:** Use :focus-visible for keyboard navigation focus, and explicitly set outline: none for :focus to prevent default browser outlines on mouse clicks.
