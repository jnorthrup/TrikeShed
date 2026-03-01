# 1BRC Benchmark Results - 1 Billion Rows

## Test Environment
- File: measurements.txt (13.28 GiB, 1,000,000,000 rows)
- Date: 2026-03-01
- Hardware: Apple Silicon (M-series)

## Results Summary

| Command | Mean [s] | Min [s] | Max [s] | Relative |
|:---|---:|---:|---:|---:|
| baseline | 127.532 ± 1.935 | 125.305 | 128.799 | 1.00 |

## Notes

- **baseline**: 127.5s (~7.85M rows/sec)
- mmap/parallel/fixedpoint variants failed due to 2GB MappedByteBuffer limit
- Only baseline variant can handle files >2GB

## Conclusion

Baseline is currently the only viable implementation for full 1BRC dataset.
To improve, need chunked mmap or streaming approach for memory-mapped variants.
