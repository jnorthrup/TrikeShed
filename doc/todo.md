# todo — RGA-driven queue

The flywheel no longer reads from a hand-curated todo list. RGA audits the
repo (gap analysis), promotes gaps into 4000-char landable tasks via the
RgaTaskPromoter, and writes WorkQueued records directly into the WAL.

If you want to seed a specific task manually, use:
  bin/trikeshed-jules create "<prompt>" "<title>"

The file is intentionally empty; the wheel fills the queue.
