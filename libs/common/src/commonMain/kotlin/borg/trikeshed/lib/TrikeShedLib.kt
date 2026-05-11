package borg.trikeshed.lib

import borg.trikeshed.cursor.Cursor
import borg.trikeshed.cursor.get

// Intentionally empty: canonical lib algebra lives under root /TrikeShed.
// Keep this path present only to overwrite stale generated JS/source-map artifacts
// from the old shim and avoid shadowing core symbols.



fun c1(c: Cursor)= c["close"]
