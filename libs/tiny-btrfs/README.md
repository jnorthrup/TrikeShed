tiny-btrfs (commonMain only)

This module contains a minimal, portable in-memory B+Tree implemented in Kotlin commonMain.
It is intended as a small, reusable core for a userspace btrfs B+tree filesystem implementation.

Guidelines:
- Keep platform-specific I/O and on-disk layout in platform modules (jvmMain/posixMain) or adapters.
- Reuse borg.trikeshed.lib primitives (Series/Join) when mapping to TrikeShed algebra is desired.
- This skeleton focuses on correctness and portability; performance and on-disk mapping are future work.

Files:
- src/commonMain/kotlin/borg/trikeshed/tinybtrfs/BPlusTree.kt  -- in-memory B+tree skeleton

