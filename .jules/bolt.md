## 2024-09-28 - Avoid Array allocations in Series mapping
**Learning:** In TrikeShed, `Series` mapping operations can accidentally allocate intermediate `ArrayList` collections if standard Kotlin `.map { ... }.toSeries()` or `.mapNotNull { ... }.toSeries()` functions are used.
**Action:** Use the `Series` mapped projection constructor via size (e.g., `list.size j { i -> list[i].mapped() }`) instead of intermediate collections mapped via standard `.map`.
