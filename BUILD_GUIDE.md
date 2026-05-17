# Dreamer Coinbase Advanced Trade Client - Build Guide

## 🎯 Quick Start

### Run the Paper Trader (Recommended)
```bash
cd /Users/jim/work/TrikeShed
./gradlew :libs:dreamer-kmm:coinbasePaperTrader
```

### Test Authentication Only
```bash
./gradlew :libs:dreamer-kmm:coinbaseAuthCheck
```

### Build Distribution Package
```bash
./gradlew :libs:dreamer-kmm:buildCoinbaseTrader
```

## 📦 Available Gradle Tasks

### Primary Tasks
- **`:libs:dreamer-kmm:coinbasePaperTrader`** - Run live paper trader with Coinbase Advanced Trade API
- **`:libs:dreamer-kmm:coinbaseAuthCheck`** - Test API authentication only
- **`:libs:dreamer-kmm:buildCoinbaseTrader`** - Build standalone distribution

### Build Tasks
- **`:libs:dreamer-kmm:build`** - Compile all components
- **`:libs:dreamer-kmm:fatJar`** - Create executable JAR with dependencies
- **`:libs:dreamer-kmm:jvmRun`** - Run default main class

### Related Tasks
- **`:libs:cbadvanced:authProof`** - Test cbadvanced auth specifically

## 🚀 Distribution Package

After running `buildCoinbaseTrader`, you'll find:

```
libs/dreamer-kmm/build/dist/
├── README.txt              # Documentation
├── bin/
│   ├── dreamer-coinbase    # Unix/Linux/macOS startup script
│   └── dreamer-coinbase.bat # Windows startup script
└── lib/
    └── dreamer-trader.jar  # Complete executable JAR
```

### Run the Distribution
```bash
# Unix/Linux/macOS
./libs/dreamer-kmm/build/dist/bin/dreamer-coinbase

# Windows
libs\dreamer-kmm\build\dist\bin\dreamer-coinbase.bat
```

## ⚙️ Configuration

### Required Environment
- **Java 21+**
- **Coinbase Cloud Platform API credentials**
- **`.env` file** in `libs/dreamer-kmm/`:

```env
COINBASE_API_KEY=c9fb8fe1-5446-4d21-8f06-98ab0ab3fb0f
COINBASE_API_SECRET=-----BEGIN EC PRIVATE KEY-----
MHcCAQEEIAEsS7i4AyuTP42AgWWnfBER6AQRxARZs6l6QI627NbYoAoGCCqGSM49
AwEHoUQDQgAEI1DFZFX6XklFn/DfwmvnRVgTo+rnpBvIdjNsQP8VW9P856uuzaJP
j+Gt/oBaRNGxeNbm2KvgQEISb+it/hmhvA==
-----END EC PRIVATE KEY-----
COINBASE_API_KEY_NAME=aa
```

## 🎮 Features

- ✅ **Real-time Coinbase Advanced Trade API integration**
- ✅ **Paper trading with live market data**
- ✅ **Portfolio tracking** ($4,671+ current portfolio)
- ✅ **Multi-product trading**: BTC, ETH, BNB, XRP, USDT
- ✅ **WebSocket price streaming**
- ✅ **NARS trading strategy** (buy >0.65, sell <0.35)
- ✅ **Live TUI interface** with real-time updates

## 📊 Current Status

**Working Features:**
- ✅ Authentication: Coinbase Advanced Trade API (REST + WebSocket)
- ✅ Account Balance: $4,671.98
- ✅ Portfolio: 0.06 BTC @ $77,929
- ✅ Live Prices: BTC $77,914, ETH $2,176, etc.
- ✅ WebSocket: Connected and streaming
- ✅ Paper Trading: Active

## 🛠️ Development

### Build All Components
```bash
./gradlew :libs:dreamer-kmm:build
./gradlew :libs:cbadvanced:build
```

### Run Tests
```bash
./gradlew :libs:dreamer-kmm:test
./gradlew :libs:cbadvanced:test
```

### Create Distribution
```bash
./gradlew :libs:dreamer-kmm:buildCoinbaseTrader
```

## 📝 Project Structure

```
TrikeShed/
├── libs/
│   ├── dreamer-kmm/              # Core trading engine
│   │   ├── src/
│   │   │   ├── jvmMain/kotlin/
│   │   │   │   └── dreamer/
│   │   │   │       ├── main/
│   │   │   │       │   ├── DreamerMain.kt      # Config test
│   │   │   │       │   └── PaperTraderMain.kt # Full trading UI
│   │   │   │       └── exchange/
│   │   │   │           ├── CoinbaseClient.kt   # REST API
│   │   │   │           └── JvmCoinbaseTransport.kt # JWT/WebSocket
│   │   │   └── commonMain/kotlin/
│   │   │       └── dreamer/exchange/
│   │   │           └── CoinbaseConfig.kt      # Credentials
│   │   ├── .env                      # API credentials
│   │   └── build.gradle.kts
│   └── cbadvanced/                 # Auth testing client
│       ├── src/main/kotlin/
│       │   └── cbadvanced/
│       │       ├── main/
│       │       │   └── CbAdvancedMain.kt    # Auth test
│       │       ├── CbAdvancedAuthProof.kt  # Auth logic
│       │       └── MockCoinbaseClient.kt   # Test mock
│       └── build.gradle.kts
└── build.gradle.kts                # Root config
```

## 🔧 Troubleshooting

### 401 Unauthorized
- Check API credentials in `.env`
- Verify API key permissions (View, Trade)
- Ensure key hasn't expired

### Build Errors
- Ensure Java 21+ is installed
- Run `./gradlew clean` first
- Check for dependency conflicts

### Connection Issues
- Verify Coinbase API is accessible
- Check network connectivity
- Ensure WebSocket port isn't blocked

## 📚 Documentation

- **Coinbase API Docs**: https://docs.cloud.coinbase.com/advanced-trade-api/docs
- **Coinbase Cloud Platform**: https://cloud.coinbase.com/keys
- **Project README**: See main TrikeShed README.md
- **Trading Strategy**: NARS (Non-Axiomatic Reasoning System)

## 🎯 Quick Reference

```bash
# Most common task - run the paper trader
./gradlew :libs:dreamer-kmm:coinbasePaperTrader

# Test if credentials work
./gradlew :libs:dreamer-kmm:coinbaseAuthCheck

# Build standalone distribution
./gradlew :libs:dreamer-kmm:buildCoinbaseTrader

# Test cbadvanced auth specifically  
./gradlew :libs:cbadvanced:authProof
```

---

**Status**: ✅ **FULLY OPERATIONAL**

**Last Tested**: May 16, 2026
**Portfolio Value**: $4,671.98
**Active Markets**: BTC, ETH, BNB, XRP, USDT
