## 2024-05-14 - SupervisorObservable notification
**Learning:** Found O(N) allocation in observer pattern loop in `SupervisorObservable.kt` that creates a list on each notification via `.values.toList().forEach`.
**Action:** Need to replace it with a pattern that avoids creating a new list on every iteration.
