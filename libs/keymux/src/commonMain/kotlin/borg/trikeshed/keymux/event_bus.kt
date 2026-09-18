package borg.trikeshed.keymux

import kotlinx.serialization.Serializable
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Represents a keyboard modifier key.
 */
@Serializable
enum class Modifier {
    CTRL,
    ALT,
    SHIFT,
    SUPER,
    META;

    override fun toString(): String = when (this) {
        CTRL -> "Ctrl"
        ALT -> "Alt"
        SHIFT -> "Shift"
        SUPER -> "Super"
        META -> "Meta"
    }

    companion object {
        fun parse(s: String): Modifier? = when (s.lowercase()) {
            "ctrl", "control" -> CTRL
            "alt" -> ALT
            "shift" -> SHIFT
            "super", "cmd", "command" -> SUPER
            "meta" -> META
            else -> null
        }
    }
}

/**
 * Represents a key chord (combination of modifiers + a key).
 */
@Serializable
data class KeyChord(
    val modifiers: List<Modifier> = emptyList(),
    val key: String
) {
    override fun toString(): String {
        if (modifiers.isEmpty()) return key
        return "${modifiers.joinToString("+")}+$key"
    }

    companion object {
        fun parse(s: String): KeyChord? {
            val parts = s.split('+')
            if (parts.isEmpty()) return null

            val key = parts.last()
            val modifiers = parts.dropLast(1)
                .mapNotNull { Modifier.parse(it) }

            return KeyChord(modifiers, key)
        }
    }
}

/**
 * Represents a key event from the event bus.
 */
@Serializable
data class KeyEvent(
    val chord: KeyChord,
    val timestamp: Long = System.currentTimeMillis(),
    val rawKey: String = ""
)

/**
 * Event bus for distributing key events to subscribers.
 * Uses a SharedFlow for multi-subscriber support.
 */
class EventBus private constructor(
    private val flow: MutableSharedFlow<KeyEvent>
) {
    companion object {
        fun create(capacity: Int = 1024): EventBus {
            return EventBus(MutableSharedFlow(replay = capacity, extraBufferCapacity = capacity))
        }
    }

    /**
     * Subscribe to key events.
     */
    fun subscribe(): SharedFlow<KeyEvent> {
        return flow.asSharedFlow()
    }

    /**
     * Publish a key event to all subscribers.
     */
    suspend fun publish(event: KeyEvent) {
        flow.emit(event)
    }

    /**
     * Close the event bus.
     */
    fun close() {
        // SharedFlow doesn't have a close method, but we can cancel the coroutine scope
        // that manages it. For now, just leave it open.
    }
}