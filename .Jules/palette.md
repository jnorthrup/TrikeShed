## 2023-10-25 - Prevent jarring focus rings on interactive table cells
**Learning:** Adding `tabindex="0"` to `td` elements makes them focusable for keyboard navigation, but using the standard `:focus` pseudo-class causes jarring focus rings when mouse users click on them.
**Action:** Use `:focus-visible` instead of `:focus` for `td` focus states, and explicitly set `outline: none;` on the base element to override browser defaults, ensuring clean mouse interactions and accessible keyboard navigation.
