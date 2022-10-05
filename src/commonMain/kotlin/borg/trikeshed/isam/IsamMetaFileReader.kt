package borg.trikeshed.isam

/**
 * 1. create a class that can read the metadata file and create a collection of record constraints
 *
 * the isam metafile format follows this sample
 *
```
# format:  coords WS .. EOL names WS .. EOL TypeMememento WS ..
# last coord is the recordlen
0 12 12 24 24 32 32 40 40 48 48 56 56 64 64 72 72 76 76 84 84 92
Open_time Close_time Open High Low Close Volume Quote_asset_volume Number_of_trades Taker_buy_base_asset_volume Taker_buy_quote_asset_volume
IoInstant IoInstant IoDouble IoDouble IoDouble IoDouble IoDouble IoDouble IoInt IoDouble IoDouble
```
 */
expect class IsamMetaFileReader {
    /**
     * 1. open the metafile descriptor for reading
    1.  mmap the file into memory
    1. close the file descriptor
    1.  parse the file into a collection of record constraints
    1. update recordlen
    1. track meta isam field constraints:  name, type, begin, end , decoder, encoder
    1. sanity check begin and end against defined IOMemento networkSizes
    1. log (DEBUG) some dimension features and statistics about the record layouts
    1.  return the collection of record constraints
     */
    fun open()

}