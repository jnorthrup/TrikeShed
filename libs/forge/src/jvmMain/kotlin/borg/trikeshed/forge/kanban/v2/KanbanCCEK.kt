package borg.trikeshed.forge.kanban.v2

/**
 * The muxer CCEK implementation moved to root commonMain reactor ownership:
 * `borg.trikeshed.userspace.reactor.MuxReactorElement`.
 *
 * Kanban consumes reactor state; it does not own keymux/modelmux pools.
 * This JVM source is intentionally kept as a package marker so stale imports
 * fail loudly instead of silently using a Kanban-owned muxer copy.
 */
