## 2024-05-24 - Accessibility aria-labels on icon-only and textual buttons
**Learning:** Found several buttons across `futon.html`, `index.html`, and `graal.html` that lacked `aria-label`s, which is critical for screen readers to describe actions accurately. Although some had `title` attributes, adding explicit `aria-label` ensures compatibility.
**Action:** Always add explicit `aria-label` attributes to `button` elements to describe their action cleanly to screen readers, even if visual text is present but might not be fully descriptive out of context.
