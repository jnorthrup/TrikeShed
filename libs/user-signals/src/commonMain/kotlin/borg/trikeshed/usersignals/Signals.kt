package borg.trikeshed.usersignals

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*

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

internal class SampledSignal<T>(private val source: Signal<T>, private val intervalMillis: Long) : Signal<T> {
    override val value: T get() = source.value
    override val changes: Flow<T> = source.changes.distinctUntilChanged().debounce(intervalMillis)
}

internal fun <T> Channel<T>.asFlow(): Flow<T> = channelFlow { while (true) { send(receive()) } }

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
    val options: List<T>
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
    val positions: List<T>
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
// FACTORY FUNCTIONS
// ====================================================================

fun toggle(initial: Boolean = false): Toggle = ToggleImpl(initial)
fun idiotLight(initial: Boolean = false): IdiotLight = IdiotLightImpl(initial)
fun momentaryButton(): MomentaryButton = MomentaryButtonImpl()
fun <T> radioToggle(options: List<T>, initial: T? = null): RadioToggle<T> = RadioToggleImpl(options, initial)
fun slider(min: Double, max: Double, initial: Double? = null, step: Double? = null): Slider = SliderImpl(min, max, initial ?: min, step)
fun knob(min: Double = 0.0, max: Double = 1.0, initial: Double = 0.0, detents: Int? = null): Knob = KnobImpl(min, max, initial, detents)
fun <T> dial(positions: List<T>, initial: T? = null): Dial<T> = DialImpl(positions, initial ?: positions.first())
fun levelMeter(peakHoldMillis: Long = 1000): LevelMeter = LevelMeterImpl(peakHoldMillis)

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
    override suspend fun pulse(intervalMillis: Long, count: Int): Sequence<Boolean> = sequence { repeat(count) { value = true; _channel.trySend(true); yield(true); kotlinx.coroutines.runBlocking { delay(intervalMillis / 2) }; value = false; _channel.trySend(false); yield(false); kotlinx.coroutines.runBlocking { delay(intervalMillis / 2) } } }
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

internal class RadioToggleImpl<T>(override val options: List<T>, initial: T?) : RadioToggle<T>, SignalSource<T> {
    override var value: T = initial ?: options.first()
        private set
    override val selected: T? get() = value
    private val _channel = Channel<T>(Channel.UNLIMITED)
    override val changes: Flow<T> = _channel.asFlow()
    override fun select(option: T): Boolean { if (option in options) { value = option; _channel.trySend(value); return true }; return false }
    override fun clear(): Boolean { value = options.first(); _channel.trySend(value); return true }
    override fun emit(value: T): T { this.value = value; _channel.trySend(value); return value }
    override suspend fun emitSuspend(value: T): T { this.value = value; _channel.send(value); return value }
}

internal class SliderImpl(override val min: Double, override val max: Double, initial: Double, override val step: Double?) : Slider, SignalSource<Double> {
    override var value: Double = initial.coerceIn(min, max)
        private set
    private val _channel = Channel<Double>(Channel.UNLIMITED)
    override val changes: Flow<Double> = _channel.asFlow()
    override fun setValue(newValue: Double): Double { value = newValue.coerceIn(min, max); if (step != null) value = kotlin.math.round(value / step!!) * step!!; _channel.trySend(value); return value }
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
    override fun setValue(newValue: Double): Double { value = newValue.coerceIn(min, max); if (detents != null) { val range = max - min; val step = range / detents!!; value = kotlin.math.round(value / step) * step }; _channel.trySend(value); return value }
    override fun rotateBy(delta: Double): Double = setValue(value + delta)
    override fun snapToDetent(): Double = setValue(value)
    override fun emit(value: Double): Double { this.value = value.coerceIn(min, max); _channel.trySend(this.value); return this.value }
    override suspend fun emitSuspend(value: Double): Double { this.value = value.coerceIn(min, max); _channel.send(this.value); return this.value }
}

internal class DialImpl<T>(override val positions: List<T>, initial: T) : Dial<T>, SignalSource<T> {
    init { require(positions.isNotEmpty()) { "Dial must have at least one position" }; require(initial in positions) { "Initial position must be in positions list" } }
    override var value: T = initial
        private set
    override val index: Int get() = positions.indexOf(value)
    private val _channel = Channel<T>(Channel.UNLIMITED)
    override val changes: Flow<T> = _channel.asFlow()
    override fun next(): T { val nextIndex = (index + 1) % positions.size; value = positions[nextIndex]; _channel.trySend(value); return value }
    override fun previous(): T { val prevIndex = (index - 1 + positions.size) % positions.size; value = positions[prevIndex]; _channel.trySend(value); return value }
    override fun goto(position: T): Boolean { if (position in positions) { value = position; _channel.trySend(value); return true }; return false }
    override fun emit(value: T): T { this.value = value; _channel.trySend(value); return value }
    override suspend fun emitSuspend(value: T): T { this.value = value; _channel.send(value); return value }
}

internal class LevelMeterImpl(override val peakHoldMillis: Long) : LevelMeter, SignalSource<Double> {
    override var value: Double = 0.0
        private set
    override val level: Double get() = value
    private var peakValue: Double = 0.0
    private var peakTimestamp: Long = 0
    override val peak: Double get() = if (System.currentTimeMillis() - peakTimestamp < peakHoldMillis) peakValue else 0.0
    private val _channel = Channel<Double>(Channel.UNLIMITED)
    override val changes: Flow<Double> = _channel.asFlow()
    override fun resetPeak(): Double { peakValue = 0.0; peakTimestamp = 0; return 0.0 }
    override fun setLevel(level: Double): Double { value = level.coerceIn(0.0, 1.0); if (value > peakValue) { peakValue = value; peakTimestamp = System.currentTimeMillis() }; _channel.trySend(value); return value }
    override fun emit(value: Double): Double { this.value = value.coerceIn(0.0, 1.0); if (this.value > peakValue) { peakValue = this.value; peakTimestamp = System.currentTimeMillis() }; _channel.trySend(this.value); return this.value }
    override suspend fun emitSuspend(value: Double): Double { this.value = value.coerceIn(0.0, 1.0); if (this.value > peakValue) { peakValue = this.value; peakTimestamp = System.currentTimeMillis() }; _channel.send(this.value); return this.value }
}