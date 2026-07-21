import sys
from unittest.mock import MagicMock, patch
from pathlib import Path

# Handle missing environment dependencies gracefully for the test
# We mock them ONLY if they are not already present in the environment
# and we do it in a way that allows us to run this specific test.
MOCKED_MODULES = []
for module_name in ['pandas', 'numpy', 'requests']:
    if module_name not in sys.modules:
        mock_mod = MagicMock()
        sys.modules[module_name] = mock_mod
        MOCKED_MODULES.append(module_name)

import pytest

# Now import the classes after handling dependencies
from data.public_binance_loader import PublicBinanceLoader, PublicBinanceConfig

def test_load_symbol_existing_file_error_handling(tmp_path):
    """
    Test that load_symbol handles an error when reading an existing feather file
    and falls back to fetching new data.
    """
    # Setup config with a temporary data directory
    data_dir = tmp_path / "data"
    data_dir.mkdir()
    config = PublicBinanceConfig(
        symbols=["BTCUSDT"],
        timeframes=["1m"],
        data_dir=str(data_dir),
        skip_existing=True,
        enable_synthetic=False
    )
    
    loader = PublicBinanceLoader(config)
    symbol = "BTCUSDT"
    timeframe = "1m"
    filename = f"{symbol}_{timeframe}.feather"
    filepath = data_dir / filename
    
    # Create a dummy file so filepath.exists() returns True
    filepath.write_text("corrupt or existing data")
    
    # Mocking internal methods and pd.read_feather
    # We use import_module style to get the actual mocked pandas from sys.modules
    import pandas as pd
    
    mock_df = MagicMock()
    mock_df.empty = False
    mock_reset_df = MagicMock()
    mock_df.reset_index.return_value = mock_reset_df

    with patch.object(pd, 'read_feather', side_effect=Exception("Corrupt feather file")), \
         patch.object(PublicBinanceLoader, '_fetch_klines', return_value=mock_df) as mock_fetch, \
         patch.object(PublicBinanceLoader, '_calculate_technical_indicators', return_value=mock_df) as mock_calc:
        
        result_df = loader.load_symbol(symbol, timeframe)
        
        # 1. Verify pd.read_feather was called (and it failed based on side_effect)
        pd.read_feather.assert_called_once_with(filepath)
        
        # 2. Verify fallback to fetching data occurred
        mock_fetch.assert_called_once()
        
        # 3. Verify technical indicators were calculated on the new data
        mock_calc.assert_called_once_with(mock_df)
        
        # 4. Verify the result is the new data
        assert result_df == mock_df
        
        # 5. Verify it attempted to save the new data
        mock_reset_df.to_feather.assert_called_once_with(filepath)

# Cleanup sys.modules for other potential tests in the same process
# (Though in this specific environment, they were likely already missing)
def teardown_module(module):
    for module_name in MOCKED_MODULES:
        if module_name in sys.modules:
            del sys.modules[module_name]
