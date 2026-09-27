## 2024-05-24 - Interactive Element Focus Outlines
**Learning:** By default, browsers add a generic focus outline to all interactive elements on click, which can be jarring. Setting `outline:none` removes this accessibility aid entirely for keyboard users.
**Action:** Use `:focus-visible` to style keyboard navigation focus uniquely, and explicitly use `:focus:not(:focus-visible) { outline: none; }` to suppress default outlines for mouse interactions to preserve the best of both worlds.
