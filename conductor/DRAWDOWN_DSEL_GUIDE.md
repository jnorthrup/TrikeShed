# Drawdown DSEL - Usage Guide

## Overview

The Drawdown DSEL (Domain-Specific Embedded Language) provides differentiable drawdown calculations and risk metrics for pretesting and paper-testing trading strategies using Kotlingrad automatic differentiation.

## Features

### 1. Drawdown Calculations

```kotlin
import borg.trikeshed.grad.*
import borg.trikeshed.lib.*

// Create equity curve
val equity = 252 j { i: Int -> 100.0 * (1.0 + i * 0.001) }

// Calculate drawdown series
val drawdowns = equity.drawdownSeries

// Maximum drawdown
val mdd = equity.maxDrawdown

// Drawdown duration
val durations = equity.drawdownDuration
val maxDuration = equity.maxDrawdownDuration
```

### 2. Risk Metrics

```kotlin
// Ulcer Index (RMS of drawdowns - "pain" metric)
val ulcer = equity.ulcerIndex

// Pain Index (average drawdown)
val pain = equity.painIndex

// Calmar Ratio (annualized return / max drawdown)
val calmar = equity.calmarRatio(periodsPerYear = 252)

// Recovery Factor (net profit / max drawdown)
val recovery = equity.recoveryFactor()
```

### 3. Pretesting

Pretesting validates strategy performance against minimum requirements:

```kotlin
// Configure pretest constraints
val config = PretestConfig(
    maxDrawdown = 0.20,        // Max 20% drawdown
    minCalmarRatio = 2.0,      // Minimum Calmar ratio
    minRecoveryFactor = 3.0,   // Minimum recovery factor
    maxUlcerIndex = 0.10,      // Max ulcer index
    minPeriods = 100           // Minimum test periods
)

// Run pretest
val result = equity.pretest(config)

// Check results
if (result.passed) {
    println("✅ Strategy passed pretesting")
} else {
    println("❌ Strategy failed:")
    result.failures.forEach { println("  - $it") }
}

// Access metrics
println("Max Drawdown: ${result.metrics["maxDrawdown"]}")
println("Calmar Ratio: ${result.metrics["calmarRatio"]}")
println("Total Return: ${result.metrics["totalReturn"]}")

// Print summary
println(result.summary())
```

### 4. Paper Testing

Paper testing simulates trading with realistic conditions:

```kotlin
// Price series and signals
val prices = 252 j { i: Int -> 100.0 + i * 0.5 }
val signals = 252 j { i: Int ->
    when {
        i % 20 == 0 -> 1    // Buy signal
        i % 20 == 10 -> -1  // Sell signal
        else -> 0           // Hold
    }
}

// Configure paper test
val config = PaperTestConfig(
    initialCapital = 100000.0,
    commissionRate = 0.001,      // 0.1% per trade
    slippagePct = 0.0005,        // 0.05% slippage
    positionSizePct = 0.10,      // 10% of capital per position
    maxPositions = 10
)

// Run paper test
val paperResult = paperTest(prices, signals, config)

// Access results
println("Total Return: ${paperResult.totalReturn * 100}%")
println("Sharpe Ratio: ${paperResult.sharpeRatio}")
println("Win Rate: ${paperResult.winRate * 100}%")
println("Profit Factor: ${paperResult.profitFactor}")
println("Total Trades: ${paperResult.trades.size}")

// Access individual trades
paperResult.trades.forEach { trade ->
    println("Trade ${trade.entryIndex}→${trade.exitIndex}: ${trade.pnlPct * 100}%")
}

// Print summary
println(paperResult.summary())
```

### 5. Position Sizing (Optimal F)

Calculate optimal position sizing using Kelly criterion:

```kotlin
// Historical trade P&L series
val trades = 50 j { i: Int ->
    when {
        i % 3 == 0 -> 150.0   // Wins
        i % 3 == 1 -> -75.0   // Losses
        else -> 100.0
    }
}

// Calculate optimal F
val optF = trades.optimalF(maxRisk = 0.25)

println("Optimal fraction: ${optF.fraction * 100}%")
println("Win rate: ${optF.winRate * 100}%")
println("Win/Loss ratio: ${optF.winLossRatio}")
```

### 6. Differentiable Operations

All metrics support automatic differentiation for optimization:

```kotlin
import borg.trikeshed.grad.*

// Lift equity to expression space
val equityFun = 10 j { i: Int -> (100.0 + i * 2).lift }

// Differentiable drawdown series
val ddFun = equityFun.drawdownSeries()

// Differentiable maximum drawdown
val mddFun = equityFun.maxDrawdown()

// Differentiable Ulcer Index
val ulcerFun = equityFun.ulcerIndex()

// Differentiable Calmar Ratio
val calmarFun = equityFun.calmarRatio()

// Evaluate at a point (empty bindings for constants)
val mdd = mddFun `≈` emptyMap()
val ulcer = ulcerFun `≈` emptyMap()

// Generate differentiable signals
val threshold = 0.02.lift
val signals = equityFun.generateSignals(threshold)
```

## Integration Example

Complete workflow from signal generation to paper testing:

```kotlin
import borg.trikeshed.grad.*
import borg.trikeshed.lib.*
import borg.trikeshed.signal.*

// 1. Load or generate price data
val close = 252 j { i: Int -> 100.0 * (1.0 + i * 0.001 + kotlin.math.sin(i * 0.1) * 0.05) }

// 2. Generate trading signals
val signals = close.size j { i: Int ->
    when {
        i % 30 == 0 -> 1    // Entry every 30 bars
        i % 30 == 15 -> -1  // Exit 15 bars later
        else -> 0
    }
}

// 3. Simulate equity curve from signals
fun simulateEquity(prices: Series<Double>, signals: Series<Int>): Series<Double> {
    val equity = mutableListOf<Double>()
    var capital = 100000.0
    var position = 0.0
    var entryPrice = 0.0
    
    equity.add(capital)
    
    for (i in 0 until prices.size) {
        val price = prices[i]
        val signal = signals[i]
        
        if (signal == 1 && position == 0.0) {
            position = capital * 0.1 / price
            entryPrice = price
            capital -= position * price
        } else if ((signal == -1 || i == prices.size - 1) && position > 0) {
            capital += position * price
            position = 0.0
        }
        
        equity.add(capital + position * price)
    }
    
    return equity.size j { i: Int -> equity[i] }
}

val equity = simulateEquity(close, signals)

// 4. Run pretesting
val pretestResult = equity.pretest()
println(pretestResult.summary())

// 5. Run paper testing
val paperResult = paperTest(close, signals)
println(paperResult.summary())

// 6. Optimize if needed
if (!pretestResult.passed) {
    println("Strategy needs optimization")
    // Adjust parameters and re-test
}
```

## API Reference

### Extension Properties (Series<Double>)

| Property | Type | Description |
|----------|------|-------------|
| `drawdownSeries` | `Series<Double>` | Drawdown at each point |
| `drawdownDuration` | `Series<Int>` | Bars in drawdown at each point |
| `maxDrawdown` | `Double` | Maximum drawdown value |
| `maxDrawdownDuration` | `Int` | Longest drawdown duration |
| `ulcerIndex` | `Double` | RMS of drawdowns |
| `painIndex` | `Double` | Average drawdown |

### Extension Functions (Series<Double>)

| Function | Returns | Description |
|----------|---------|-------------|
| `calmarRatio(periodsPerYear)` | `Double` | Annualized return / MDD |
| `recoveryFactor()` | `Double` | Net profit / MDD |
| `optimalF(maxRisk)` | `OptimalF` | Kelly position sizing |
| `pretest(config)` | `PretestResult` | Validate against constraints |

### Extension Functions (Series<SFun<DReal>>)

| Function | Returns | Description |
|----------|---------|-------------|
| `drawdownSeries()` | `Series<SFun<DReal>>` | Differentiable drawdowns |
| `maxDrawdown()` | `SFun<DReal>` | Differentiable MDD |
| `ulcerIndex()` | `SFun<DReal>` | Differentiable Ulcer Index |
| `painIndex()` | `SFun<DReal>` | Differentiable Pain Index |
| `calmarRatio(periodsPerYear)` | `SFun<DReal>` | Differentiable Calmar |
| `recoveryFactor()` | `SFun<DReal>` | Differentiable Recovery |
| `generateSignals(threshold)` | `Series<SFun<DReal>>` | Differentiable signals |

### Standalone Functions

| Function | Returns | Description |
|----------|---------|-------------|
| `paperTest(prices, signals, config)` | `PaperTestResult` | Simulate trading |
| `optimizeStopLoss(equity, maxMDD)` | `Double` | Find optimal stop loss |

## Data Classes

### PretestConfig
```kotlin
data class PretestConfig(
    val maxDrawdown: Double = 0.20,
    val minCalmarRatio: Double = 2.0,
    val minRecoveryFactor: Double = 3.0,
    val maxUlcerIndex: Double = 0.10,
    val minPeriods: Int = 100,
    val periodsPerYear: Int = 252
)
```

### PretestResult
```kotlin
data class PretestResult(
    val passed: Boolean,
    val metrics: Map<String, Double>,
    val failures: List<String>,
    val equityCurve: Series<Double>
)
```

### PaperTestConfig
```kotlin
data class PaperTestConfig(
    val initialCapital: Double = 100000.0,
    val commissionRate: Double = 0.001,
    val slippagePct: Double = 0.0005,
    val positionSizePct: Double = 0.10,
    val maxPositions: Int = 10,
    val periodsPerYear: Int = 252
)
```

### PaperTestResult
```kotlin
data class PaperTestResult(
    val equityCurve: Series<Double>,
    val trades: List<Trade>,
    val totalReturn: Double,
    val annualizedReturn: Double,
    val sharpeRatio: Double,
    val maxDrawdown: Double,
    val winRate: Double,
    val profitFactor: Double
)
```

### Trade
```kotlin
data class Trade(
    val entryIndex: Int,
    val exitIndex: Int,
    val entryPrice: Double,
    val exitPrice: Double,
    val quantity: Double,
    val pnl: Double,
    val pnlPct: Double,
    val isLong: Boolean
)
```

## Notes

- All drawdown calculations use the standard definition: `(peak - current) / peak`
- Drawdown values are expressed as decimals (0.15 = 15% drawdown)
- Differentiable functions require lifting values with `.lift` extension
- Evaluation of differentiable functions uses the `` `≈` `` operator with bindings
- Paper testing includes realistic frictions: commissions and slippage
- Pretesting is a gate before paper/live testing to filter poor strategies
