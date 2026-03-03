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
