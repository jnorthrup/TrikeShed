package borg.trikeshed.usersignals

import borg.trikeshed.lib.Series
import borg.trikeshed.lib.get
import borg.trikeshed.lib.j
import borg.trikeshed.lib.size
import borg.trikeshed.lib.toList
import borg.trikeshed.usersignals.platform.currentTimeMillis
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlin.math.max
import kotlin.math.min

/**
 * User-signalling abstractions for 0D and 1D features (idiot lights, toggles, sliders, knobs).
 */

// ====================================================================
// BASE SIGNAL ALGEBRA
// ====================================================================

interface Signal<out T> {
    val value: T
    val changes: Flow<T>
    fun <R> map(transform: (T) -> R): Signal<R> = MappedSignal(this, transform)
    fun <U, R> combine(other: Signal<U>, combiner: (T, U) -> R): Signal<R> = CombinedSignal(this, other, combiner)
    fun filter(predicate: (T) -> Boolean): Signal<T> = FilteredSignal(this, predicate)
    fun sample(intervalMillis: Long): Signal<T> = SampledSignal(this, intervalMillis)

    companion object {
        fun <T> Const(value: T): Signal<T> = constSignal(value)
    }
}

interface SignalSource<T> : Signal<T> {
    fun emit(value: T): T
    suspend fun emitSuspend(value: T): T
}

internal class MappedSignal<T, R>(private val source: Signal<T>, private val transform: (T) -> R) : Signal<R> {
    override val value: R get() = transform(source.value)
    override val changes: Flow<R> = source.changes.map(transform)
}

internal class CombinedSignal<T, U, R>(private val source1: Signal<T>, private val source2: Signal<U>, private val combiner: (T, U) -> R) : Signal<R> {
    override val value: R get() = combiner(source1.value, source2.value)
    override val changes: Flow<R> = kotlinx.coroutines.flow.combine(source1.changes, source2.changes) { a, b -> combiner(a, b) }
}

internal class FilteredSignal<T>(private val source: Signal<T>, private val predicate: (T) -> Boolean) : Signal<T> {
    override val value: T get() = source.value
    override val changes: Flow<T> = source.changes.filter(predicate)
}

@OptIn(kotlinx.coroutines.FlowPreview::class)
internal class SampledSignal<T>(private val source: Signal<T>, private val intervalMillis: Long) : Signal<T> {
    override val value: T get() = source.value
    override val changes: Flow<T> = source.changes.distinctUntilChanged().debounce(intervalMillis)
}

internal fun <T> Channel<T>.asFlow(): Flow<T> = channelFlow { while (true) { send(receive()) } }

// Constant signal - a signal that never changes
fun <T> constSignal(value: T): Signal<T> = ConstSignal(value)

internal class ConstSignal<T>(private val constValue: T) : Signal<T> {
    override val value: T = constValue
    override val changes: Flow<T> = kotlinx.coroutines.flow.flowOf(constValue)
}

// ====================================================================
// 0-DIMENSIONAL SIGNALS
// ====================================================================

interface Toggle : Signal<Boolean> {
    val isOn: Boolean
    fun toggle(): Boolean
    fun turnOn(): Boolean
    fun turnOff(): Boolean
}

interface IdiotLight : Signal<Boolean> {
    val isLit: Boolean
    suspend fun flash(durationMillis: Long): Boolean
    suspend fun pulse(intervalMillis: Long, count: Int): Sequence<Boolean>
}

interface MomentaryButton : Signal<Boolean> {
    val isPressed: Boolean
    fun press(): Boolean
    fun release(): Boolean
    suspend fun tap(): Boolean
}

interface RadioToggle<T> : Signal<T> {
    val selected: T?
    val optionSeries: Series<T>
    val options: List<T> get() = optionSeries.toList()
    fun select(option: T): Boolean
    fun clear(): Boolean
}

// ====================================================================
// 1-DIMENSIONAL SIGNALS
// ====================================================================

interface Slider : Signal<Double> {
    override val value: Double
    val min: Double
    val max: Double
    val step: Double?
    val normalized: Double get() = (value - min) / (max - min)
    fun setValue(newValue: Double): Double
    fun increment(): Double
    fun decrement(): Double
    fun snap(): Double
}

interface Knob : Signal<Double> {
    override val value: Double
    val min: Double
    val max: Double
    val detents: Int?
    val radians: Double get() = min + (max - min) * value
    val normalized: Double get() = (value - min) / (max - min)
    fun setValue(newValue: Double): Double
    fun rotateBy(delta: Double): Double
    fun snapToDetent(): Double
}

interface Dial<T> : Signal<T> {
    override val value: T
    val positionSeries: Series<T>
    val positions: List<T> get() = positionSeries.toList()
    val index: Int
    fun next(): T
    fun previous(): T
    fun goto(position: T): Boolean
}

interface LevelMeter : Signal<Double> {
    override val value: Double
    val level: Double get() = value
    val peak: Double
    val peakHoldMillis: Long
    fun resetPeak(): Double
    fun setLevel(level: Double): Double
}

// ====================================================================
// TEXT FIELD SIGNAL
// ====================================================================

data class TextFieldState(
    val text: String,
    val caret: Int,
    val selectionStart: Int = -1,
    val selectionEnd: Int = -1,
    val focused: Boolean = false,
    val committed: Boolean = false
) {
    val hasSelection: Boolean get() = selectionStart != selectionEnd && selectionStart >= 0 && selectionEnd >= 0
    val selectedText: String get() = if (hasSelection) text.substring(min(selectionStart, selectionEnd), max(selectionStart, selectionEnd)) else ""
}

interface TextField : Signal<TextFieldState> {
    fun focus(): TextFieldState
    fun blur(): TextFieldState
    fun insert(text: String): TextFieldState
    fun backspace(): TextFieldState
    fun deleteForward(): TextFieldState
    fun moveCaret(delta: Int): TextFieldState
    fun setSelection(start: Int, end: Int): TextFieldState
    fun clear(): TextFieldState
    fun commit(): TextFieldState
}

// ====================================================================
// FACTORY FUNCTIONS
// ====================================================================

fun toggle(initial: Boolean = false): Toggle = ToggleImpl(initial)
fun idiotLight(initial: Boolean = false): IdiotLight = IdiotLightImpl(initial)
fun momentaryButton(): MomentaryButton = MomentaryButtonImpl()
fun <T> radioToggle(options: Series<T>, initial: T? = null): RadioToggle<T> = RadioToggleImpl(options, initial)
fun <T> radioToggle(options: List<T>, initial: T? = null): RadioToggle<T> = radioToggle(options.size j { index -> options[index] }, initial)
fun slider(min: Double, max: Double, initial: Double? = null, step: Double? = null): Slider = SliderImpl(min, max, initial ?: min, step)
fun knob(min: Double = 0.0, max: Double = 1.0, initial: Double = 0.0, detents: Int? = null): Knob = KnobImpl(min, max, initial, detents)
fun <T> dial(positions: Series<T>, initial: T? = null): Dial<T> = DialImpl(positions, initial ?: positions[0])
fun <T> dial(positions: List<T>, initial: T? = null): Dial<T> = dial(positions.size j { index -> positions[index] }, initial)
fun levelMeter(peakHoldMillis: Long = 1000): LevelMeter = LevelMeterImpl(peakHoldMillis)
fun textField(initial: String = "", placeholder: String? = null, masked: Boolean = false): TextField = TextFieldImpl(initial, placeholder, masked)

// ====================================================================
// DEFAULT IMPLEMENTATIONS
// ====================================================================

internal class ToggleImpl(initial: Boolean) : Toggle, SignalSource<Boolean> {
    override var value: Boolean = initial
        private set
    override val isOn: Boolean get() = value
    private val _channel = Channel<Boolean>(Channel.UNLIMITED)
    override val changes: Flow<Boolean> = _channel.asFlow()
    override fun toggle(): Boolean { value = !value; _channel.trySend(value); return value }
    override fun turnOn(): Boolean { value = true; _channel.trySend(value); return value }
    override fun turnOff(): Boolean { value = false; _channel.trySend(value); return value }
    override fun emit(value: Boolean): Boolean { this.value = value; _channel.trySend(value); return value }
    override suspend fun emitSuspend(value: Boolean): Boolean { this.value = value; _channel.send(value); return value }
}

internal class IdiotLightImpl(initial: Boolean) : IdiotLight, SignalSource<Boolean> {
    override var value: Boolean = initial
        private set
    override val isLit: Boolean get() = value
    private val _channel = Channel<Boolean>(Channel.UNLIMITED)
    override val changes: Flow<Boolean> = _channel.asFlow()
    override suspend fun flash(durationMillis: Long): Boolean { value = true; _channel.trySend(true); delay(durationMillis); value = false; _channel.trySend(false); return false }
    override suspend fun pulse(intervalMillis: Long, count: Int): Sequence<Boolean> {
        repeat(count) {
            value = true
            _channel.trySend(true)
            delay(intervalMillis / 2)
            value = false
            _channel.trySend(false)
            delay(intervalMillis / 2)
        }
        return Sequence {
            object : Iterator<Boolean> {
                private var index = 0
                override fun hasNext(): Boolean = index < count * 2
                override fun next(): Boolean = (index++ % 2) == 0
            }
        }
    }
    override fun emit(value: Boolean): Boolean { this.value = value; _channel.trySend(value); return value }
    override suspend fun emitSuspend(value: Boolean): Boolean { this.value = value; _channel.send(value); return value }
}

internal class MomentaryButtonImpl() : MomentaryButton, SignalSource<Boolean> {
    override var value: Boolean = false
        private set
    override val isPressed: Boolean get() = value
    private val _channel = Channel<Boolean>(Channel.UNLIMITED)
    override val changes: Flow<Boolean> = _channel.asFlow()
    override fun press(): Boolean { value = true; _channel.trySend(true); return value }
    override fun release(): Boolean { value = false; _channel.trySend(false); return value }
    override suspend fun tap(): Boolean { press(); delay(50); return release() }
    override fun emit(value: Boolean): Boolean { this.value = value; _channel.trySend(value); return value }
    override suspend fun emitSuspend(value: Boolean): Boolean { this.value = value; _channel.send(value); return value }
}

internal class RadioToggleImpl<T>(override val optionSeries: Series<T>, initial: T?) : RadioToggle<T>, SignalSource<T> {
    init { require(optionSeries.size > 0) { "RadioToggle must have at least one option" } }
    override var value: T = initial ?: optionSeries[0]
        private set
    override val selected: T? get() = value
    private val _channel = Channel<T>(Channel.UNLIMITED)
    override val changes: Flow<T> = _channel.asFlow()
    override fun select(option: T): Boolean { if (optionSeries.indexOfValue(option) >= 0) { value = option; _channel.trySend(value); return true }; return false }
    override fun clear(): Boolean { value = optionSeries[0]; _channel.trySend(value); return true }
    override fun emit(value: T): T { this.value = value; _channel.trySend(value); return value }
    override suspend fun emitSuspend(value: T): T { this.value = value; _channel.send(value); return value }
}

internal class SliderImpl(override val min: Double, override val max: Double, initial: Double, override val step: Double?) : Slider, SignalSource<Double> {
    override var value: Double = initial.coerceIn(min, max)
        private set
    private val _channel = Channel<Double>(Channel.UNLIMITED)
    override val changes: Flow<Double> = _channel.asFlow()
    override fun setValue(newValue: Double): Double { value = newValue.coerceIn(min, max); step?.let { value = kotlin.math.round(value / it) * it }; _channel.trySend(value); return value }
    override fun increment(): Double = setValue(value + (step ?: (max - min) / 100))
    override fun decrement(): Double = setValue(value - (step ?: (max - min) / 100))
    override fun snap(): Double = setValue(value)
    override fun emit(value: Double): Double { this.value = value.coerceIn(min, max); _channel.trySend(this.value); return this.value }
    override suspend fun emitSuspend(value: Double): Double { this.value = value.coerceIn(min, max); _channel.send(this.value); return this.value }
}

internal class KnobImpl(override val min: Double, override val max: Double, initial: Double, override val detents: Int?) : Knob, SignalSource<Double> {
    override var value: Double = initial.coerceIn(min, max)
        private set
    private val _channel = Channel<Double>(Channel.UNLIMITED)
    override val changes: Flow<Double> = _channel.asFlow()
    override fun setValue(newValue: Double): Double { value = newValue.coerceIn(min, max); detents?.let { detentCount -> val range = max - min; val step = range / detentCount; value = kotlin.math.round(value / step) * step }; _channel.trySend(value); return value }
    override fun rotateBy(delta: Double): Double = setValue(value + delta)
    override fun snapToDetent(): Double = setValue(value)
    override fun emit(value: Double): Double { this.value = value.coerceIn(min, max); _channel.trySend(this.value); return this.value }
    override suspend fun emitSuspend(value: Double): Double { this.value = value.coerceIn(min, max); _channel.send(this.value); return this.value }
}

internal class DialImpl<T>(override val positionSeries: Series<T>, initial: T) : Dial<T>, SignalSource<T> {
    init { require(positionSeries.size > 0) { "Dial must have at least one position" }; require(positionSeries.indexOfValue(initial) >= 0) { "Initial position must be in positions series" } }
    override var value: T = initial
        private set
    override val index: Int get() = positionSeries.indexOfValue(value)
    private val _channel = Channel<T>(Channel.UNLIMITED)
    override val changes: Flow<T> = _channel.asFlow()
    override fun next(): T { val nextIndex = (index + 1) % positionSeries.size; value = positionSeries[nextIndex]; _channel.trySend(value); return value }
    override fun previous(): T { val prevIndex = (index - 1 + positionSeries.size) % positionSeries.size; value = positionSeries[prevIndex]; _channel.trySend(value); return value }
    override fun goto(position: T): Boolean { if (positionSeries.indexOfValue(position) >= 0) { value = position; _channel.trySend(value); return true }; return false }
    override fun emit(value: T): T { this.value = value; _channel.trySend(value); return value }
    override suspend fun emitSuspend(value: T): T { this.value = value; _channel.send(value); return value }
}

private fun <T> Series<T>.indexOfValue(value: T): Int {
    for (index in 0 until size) {
        if (this[index] == value) return index
    }
    return -1
}

internal class LevelMeterImpl(override val peakHoldMillis: Long) : LevelMeter, SignalSource<Double> {
    override var value: Double = 0.0
        private set
    override val level: Double get() = value
    private var peakValue: Double = 0.0
    private var peakTimestamp: Long = 0
    override val peak: Double get() = if (currentTimeMillis() - peakTimestamp < peakHoldMillis) peakValue else 0.0
    private val _channel = Channel<Double>(Channel.UNLIMITED)
    override val changes: Flow<Double> = _channel.asFlow()
    override fun resetPeak(): Double { peakValue = 0.0; peakTimestamp = 0; return 0.0 }
    override fun setLevel(level: Double): Double { value = level.coerceIn(0.0, 1.0); if (value > peakValue) { peakValue = value; peakTimestamp = currentTimeMillis() }; _channel.trySend(value); return value }
    override fun emit(value: Double): Double { this.value = value.coerceIn(0.0, 1.0); if (this.value > peakValue) { peakValue = this.value; peakTimestamp = currentTimeMillis() }; _channel.trySend(this.value); return this.value }
    override suspend fun emitSuspend(value: Double): Double { this.value = value.coerceIn(0.0, 1.0); if (this.value > peakValue) { peakValue = this.value; peakTimestamp = currentTimeMillis() }; _channel.send(this.value); return this.value }
}

// ====================================================================
// TEXT FIELD IMPLEMENTATION
// ====================================================================

internal class TextFieldImpl(
    initial: String,
    val placeholder: String? = null,
    val masked: Boolean = false
) : TextField, SignalSource<TextFieldState> {
    override var value: TextFieldState = TextFieldState(text = initial, caret = initial.length)
        private set

    private val _channel = Channel<TextFieldState>(Channel.UNLIMITED)
    override val changes: Flow<TextFieldState> = _channel.asFlow()

    private fun update(newState: TextFieldState): TextFieldState {
        value = newState
        _channel.trySend(newState)
        return newState
    }

    override fun focus(): TextFieldState = update(value.copy(focused = true, committed = false))

    override fun blur(): TextFieldState = update(value.copy(focused = false))

    override fun insert(text: String): TextFieldState {
        val newText = if (value.hasSelection) {
            val start = min(value.selectionStart, value.selectionEnd)
            val end = max(value.selectionStart, value.selectionEnd)
            value.text.substring(0, start) + text + value.text.substring(end)
        } else {
            value.text.substring(0, value.caret) + text + value.text.substring(value.caret)
        }
        val newCaret = if (value.hasSelection) min(value.selectionStart, value.selectionEnd) + text.length else value.caret + text.length
        return update(value.copy(text = newText, caret = newCaret, selectionStart = -1, selectionEnd = -1, committed = false))
    }

    override fun backspace(): TextFieldState {
        if (value.text.isEmpty()) return value
        val newText = if (value.hasSelection) {
            val start = min(value.selectionStart, value.selectionEnd)
            val end = max(value.selectionStart, value.selectionEnd)
            value.text.substring(0, start) + value.text.substring(end)
        } else if (value.caret > 0) {
            value.text.substring(0, value.caret - 1) + value.text.substring(value.caret)
        } else {
            value.text
        }
        val newCaret = if (value.hasSelection) min(value.selectionStart, value.selectionEnd) else value.caret - 1
        return update(value.copy(text = newText, caret = max(0, newCaret), selectionStart = -1, selectionEnd = -1, committed = false))
    }

    override fun deleteForward(): TextFieldState {
        if (value.caret >= value.text.length) return value
        val newText = if (value.hasSelection) {
            val start = min(value.selectionStart, value.selectionEnd)
            val end = max(value.selectionStart, value.selectionEnd)
            value.text.substring(0, start) + value.text.substring(end)
        } else {
            value.text.substring(0, value.caret) + value.text.substring(value.caret + 1)
        }
        val newCaret = if (value.hasSelection) min(value.selectionStart, value.selectionEnd) else value.caret
        return update(value.copy(text = newText, caret = newCaret, selectionStart = -1, selectionEnd = -1, committed = false))
    }

    override fun moveCaret(delta: Int): TextFieldState {
        val newCaret = (value.caret + delta).coerceIn(0, value.text.length)
        return update(value.copy(caret = newCaret, selectionStart = -1, selectionEnd = -1))
    }

    override fun setSelection(start: Int, end: Int): TextFieldState {
        val s = start.coerceIn(0, value.text.length)
        val e = end.coerceIn(0, value.text.length)
        return update(value.copy(selectionStart = s, selectionEnd = e, caret = e))
    }

    override fun clear(): TextFieldState = update(value.copy(text = "", caret = 0, selectionStart = -1, selectionEnd = -1, committed = false))

    override fun commit(): TextFieldState = update(value.copy(committed = true))

    override fun emit(value: TextFieldState): TextFieldState {
        this.value = value
        _channel.trySend(value)
        return value
    }

    override suspend fun emitSuspend(value: TextFieldState): TextFieldState {
        this.value = value
        _channel.send(value)
        return value
    }
}