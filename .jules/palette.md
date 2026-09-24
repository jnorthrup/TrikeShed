## 2024-09-24 - Suppress mouse click focus outlines
**Learning:** Using the standard `:focus` pseudo-class for outlines creates a jarring visual ring during mouse clicks on interactive elements. This is an accessibility anti-pattern if we want to separate mouse clicks and keyboard navigation.
**Action:** Use `:focus-visible` to style keyboard navigation focus, and explicitly use `:focus:not(:focus-visible) { outline: none; }` to suppress default browser outlines for mouse interactions. This avoids CSS cascade bugs and preserves accessibility.
