package org.xvm.runtime;

/**
 * VM lifecycle state machine: INIT -> RUNNING -> DRAINING -> SHUTDOWN.
 *
 * Used by pointcut drain pipeline to gate publisher shutdown.
 * No reverse transitions. Attempting an invalid transition throws IllegalStateException.
 */
public final class XvmLifecycle {

    public enum State { INIT, RUNNING, DRAINING, SHUTDOWN }

    private State state = State.INIT;

    public State state() { return state; }

    public boolean isRunning()  { return state == State.RUNNING; }
    public boolean isDraining() { return state == State.DRAINING; }
    public boolean isShutdown() { return state == State.SHUTDOWN; }

    public void start() {
        if (state != State.INIT) {
            throw new IllegalStateException("start() requires INIT, got " + state);
        }
        state = State.RUNNING;
    }

    public void drain() {
        if (state != State.RUNNING) {
            throw new IllegalStateException("drain() requires RUNNING, got " + state);
        }
        state = State.DRAINING;
    }

    public void shutdown() {
        if (state != State.DRAINING) {
            throw new IllegalStateException("shutdown() requires DRAINING, got " + state);
        }
        state = State.SHUTDOWN;
    }
}
