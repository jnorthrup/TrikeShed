package borg.trikeshed.couch.miniduck

import borg.trikeshed.lib.Series
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt

// Projection: Log Returns
fun logReturn(prices: Series<Double>): Series<Double> =
    prices.size j { i ->
        if (i == 0) 0.0 else ln(prices[i] / prices[i - 1])
    }

// Projection: Simple Moving Average (SMA)
fun sma(source: Series<Double>, window: Int): Series<Double> =
    source.size j { i ->
        if (i < window - 1) Double.NaN
        else (0 until window).sumOf { source[i - it] } / window
    }

// Projection: Rolling Volatility (Standard Deviation)
fun rollingStd(source: Series<Double>, window: Int): Series<Double> =
    source.size j { i ->
        if (i < window - 1) Double.NaN
        else {
            val mean = (0 until window).sumOf { source[i - it] } / window
            val variance = (0 until window).sumOf { (source[i - it] - mean).pow(2) } / window
            sqrt(variance)
        }
    }
