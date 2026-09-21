## 2023-10-27 - Sidebar Link Focus Visibility
**Learning:** Keyboard navigation on interactive links in the sidebar is visually broken because they are `a` tags but lack the generic `:focus-visible` styles that other interactive elements have (like `.topbar-btn`, `button`, etc.). This causes standard keyboard navigation users to lose context when focusing on links.
**Action:** Always include `a:focus-visible` in the list of elements that receive standard focus outline in the main `styles.css`.
