package borg.trikeshed.couch

import borg.trikeshed.couch.stream.*
import kotlin.test.*

/**
 * Red test: ChangeStream — register listener, perform insert/remove,
 * assert listener receives ordered Change events.
 *
 * Donor pattern: go-stopper stopHooks (registered callbacks fired
 * synchronously on state transitions) but generalized as a fan-out
 * ChangeEmitter. No donor has a generic pub/sub — this is a couch gap.
 *
 * Will fail to compile until ChangeEmitter, Change, and ChangeListener exist.
 */
class ChangeStreamTest {

    @Test
    fun insertEmitsChangeEvent() {
        val emitter = ChangeEmitter<CharSequence>()
        val events = mutableListOf<Change<CharSequence>>()
        emitter.register { change -> events.add(change) }

        emitter.emit(Change.Insert("doc1"))
        assertEquals(1, events.size)
        assertEquals(Change.Kind.INSERT, events[0].kind)
        assertEquals("doc1", events[0].key)
    }

    @Test
    fun removeEmitsChangeEvent() {
        val emitter = ChangeEmitter<CharSequence>()
        val events = mutableListOf<Change<CharSequence>>()
        emitter.register { change -> events.add(change) }

        emitter.emit(Change.Remove("doc1"))
        assertEquals(1, events.size)
        assertEquals(Change.Kind.REMOVE, events[0].kind)
    }

    @Test
    fun sealEmitsSealEvent() {
        val emitter = ChangeEmitter<CharSequence>()
        val events = mutableListOf<Change<CharSequence>>()
        emitter.register { change -> events.add(change) }

        emitter.emit(Change.Seal)
        assertEquals(1, events.size)
        assertEquals(Change.Kind.SEAL, events[0].kind)
    }

    @Test
    fun eventsAreDeliveredInOrder() {
        val emitter = ChangeEmitter<CharSequence>()
        val events = mutableListOf<Change<CharSequence>>()
        emitter.register { change -> events.add(change) }

        emitter.emit(Change.Insert("a"))
        emitter.emit(Change.Insert("b"))
        emitter.emit(Change.Remove("a"))
        emitter.emit(Change.Seal)

        assertEquals(4, events.size)
        assertEquals(Change.Kind.INSERT, events[0].kind)
        assertEquals("a", events[0].key)
        assertEquals(Change.Kind.INSERT, events[1].kind)
        assertEquals("b", events[1].key)
        assertEquals(Change.Kind.REMOVE, events[2].kind)
        assertEquals("a", events[2].key)
        assertEquals(Change.Kind.SEAL, events[3].kind)
    }

    @Test
    fun multipleListenersAllReceiveEvents() {
        val emitter = ChangeEmitter<CharSequence>()
        val events1 = mutableListOf<Change<CharSequence>>()
        val events2 = mutableListOf<Change<CharSequence>>()

        emitter.register { change -> events1.add(change) }
        emitter.register { change -> events2.add(change) }

        emitter.emit(Change.Insert("x"))
        assertEquals(1, events1.size)
        assertEquals(1, events2.size)
    }

    @Test
    fun unregisteredListenerStopsReceiving() {
        val emitter = ChangeEmitter<CharSequence>()
        val events = mutableListOf<Change<CharSequence>>()
        val token = emitter.register { change -> events.add(change) }

        emitter.emit(Change.Insert("a"))
        assertEquals(1, events.size)

        emitter.unregister(token)
        emitter.emit(Change.Insert("b"))
        // only first event received
        assertEquals(1, events.size)
    }

    @Test
    fun noEventsAfterSeal() {
        val emitter = ChangeEmitter<CharSequence>()
        val events = mutableListOf<Change<CharSequence>>()
        emitter.register { change -> events.add(change) }

        emitter.emit(Change.Seal)
        emitter.emit(Change.Insert("late"))
        // only the Seal event, no inserts after sealing
        assertEquals(1, events.size)
        assertEquals(Change.Kind.SEAL, events[0].kind)
    }
}
