🎯 **What:** Improved `FILE*` handling in `PosixFile.kt` and `LinuxPosixFile.kt`.

💡 **Why:** `fopen` requires manual resource management (calling `fclose`) and can leak file descriptors if exceptions occur before the file is closed. `nativeHeap.alloc()` also requires manual resource management. `memScoped` solves the memory problem natively, and by opening the file using `PosixFile` with `fdopen` we get automatic file descriptor resource cleanup handling that matches the rest of the codebase.

✅ **Verification:** Verified that tests compile and the code works identically. Used `memScoped` and `.alloc()` instead of `nativeHeap.alloc()`.

✨ **Result:** Memory management is safer, `fd` handling is correctly wrapped, and code is cleaner.
