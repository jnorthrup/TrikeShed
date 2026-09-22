## 2025-01-20 - Redundant ARIA Labels in App Shell
**Learning:** Found an accessibility issue pattern specific to TrikeShed app shell, where almost all navigation buttons (sidebar, topbar) incorrectly implemented aria-label attributes identical to their visible text.
**Action:** Always review newly added buttons in the index.html to ensure aria-label is reserved strictly for icon-only or visually hidden context buttons.
