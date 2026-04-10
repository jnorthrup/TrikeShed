/**
 * TrikeShed Platform — Userspace NIO
 *
 * Ported from literbike `src/userspace/nio/`. Non-blocking I/O with platform-specific
 * backends and reactor pattern implementation.
 *
 * - backend: NIO backend trait/interface
 * - endgame: Endgame (shutdown) handling
 * - epoll_backend: epoll backend (Linux)
 * - kqueue_backend: kqueue backend (macOS/BSD)
 * - nio_uring: NIO + io_uring integration
 * - reactor: Reactor pattern implementation
 * - session_island: Session isolation
 * - suspend_resume: Suspend/resume handling
 */
package borg.trikeshed.platform.nio
