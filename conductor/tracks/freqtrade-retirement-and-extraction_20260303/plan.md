# Plan: Freqtrade Retirement and Feature Extraction

## Purpose
Decommission freqtrade by extracting beneficial logic—specifically common indicators and signal generation patterns—and porting them into the TrikeShed algebra (core) and leaf projects (consumption).

## Bounded Corpus
- `../freqtrade/user_data/strategies/sample_strategy.py` (source: signals)
- `src/commonMain/kotlin/borg/trikeshed/indicator/` (target: Series-based indicators)
- `../moneyfan/runtime/` (target: dsel-based strategy logic)

## Tasks
- [x] Audit `freqtrade` strategy interfaces and indicator patterns (RSI, Bollinger, MACD).
- [x] Verify existing Series-based technical algebra in `DoubleSeries.kt` (SMA, EMA, Wilder's).
- [x] Map freqtrade `SampleStrategy` signal logic to modular TrikeShed objects in `Indicators.kt`.
- [x] Implement missing TA-Lib staples (Stochastic, ADX, Williams%R) in TrikeShed core.
- [x] Extract ROI/Stoploss rules from freqtrade into `moneyfan` DSEL contracts.
- [x] Port `SampleStrategy` signal logic into a testable TrikeShed module.

## 100% Criteria
- [x] Feature synchronization: TrikeShed `RSI` logic validated against TA-Lib Wilder smoothing semantics.
- [x] Logic extraction: A `moneyfan` .dsel defining the ROI/Stoploss rules from `SampleStrategy`.
- [x] Signal module: `SignalGenerator.kt` with SampleStrategy entry/exit logic and tests.

## Implementation Summary

### New Modules Created

#### `strategy/` Package
- **StrategyRules.kt**: DSEL contracts for ROI and Stoploss rules
  - `RoiRule`: Configurable ROI thresholds with time-based decay
  - `StoplossRule`: Fixed and trailing stoploss support
  - `TimeExitRule`: Maximum holding period exits
  - `StrategyRules`: Composite rule evaluator
  - Fluent DSL builder: `strategy { withRoi(...); withStoploss(...) }`

#### `signal/` Package
- **SignalGenerator.kt**: Entry/exit signal generation from technical indicators
  - `SignalConfig`: Configurable thresholds for all signal types
  - `SignalGenerator`: Multi-indicator signal fusion
  - `SampleStrategy`: Direct port of freqtrade's SampleStrategy logic
  - Extension functions: `generateSignals()`, `buySignals()`, `sellSignals()`

### Test Coverage
- **StrategyRulesTest.kt**: 15+ tests covering ROI, stoploss, time exits, and DSL
- **SignalGeneratorTest.kt**: 15+ tests covering signal generation, configurations, and edge cases

### Files Added
```
src/commonMain/kotlin/borg/trikeshed/strategy/StrategyRules.kt
src/commonMain/kotlin/borg/trikeshed/signal/SignalGenerator.kt
src/jvmTest/kotlin/borg/trikeshed/strategy/StrategyRulesTest.kt
src/jvmTest/kotlin/borg/trikeshed/signal/SignalGeneratorTest.kt
```

### Usage Example
```kotlin
// Define strategy rules
val rules = strategy {
    withRoi(RoiRule.conservative())
    withStoploss(StoplossRule.trailing())
    describedAs("My Strategy")
}

// Generate signals from price data
val close: Series<Double> = ...
val signals = close.generateSignals(SignalConfig.balanced())

// Evaluate exit conditions
val decision = rules.evaluate(
    profitRatio = 0.02,
    elapsedMinutes = 30,
    maxProfitRatio = 0.05
)
```
