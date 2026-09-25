## 2024-05-20 - Adding standard `:focus-visible` styling
**Learning:** Consistently using `:focus:not(:focus-visible) { outline: none; }` and `:focus-visible` across all web documents prevents jarring visual outlines when clicking with a mouse on focusable elements while ensuring keyboard accessibility is retained.
**Action:** Always check if focus styles are appropriately restricted to `:focus-visible` on interactive elements, and add global CSS reset rules for them in newly created HTML documents.
