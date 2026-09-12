# liburing source provenance

The C library under `../../liburing` is the build-required source subset of
[axboe/liburing](https://github.com/axboe/liburing), tag `liburing-2.15`, commit
`d41bf9220ec39277ff235379e9089d9e0fd6c2a5`.

The upstream files are unchanged: `COPYING`, `COPYING.GPL`, `LICENSE`, `Makefile`,
`Makefile.common`, `Makefile.quiet`, `configure`, `liburing.spec`, and every tracked
file under `src/`. The upstream license files apply to this source. Tests,
examples, manuals, and Git metadata are omitted from the source subset.

Gradle's `configureLiburing` and `buildLiburing` use the upstream out-of-source
build in `build/native/liburing`; generated headers and PIC static archives stay
there. `liburing.def` is the project's Kotlin cinterop declaration. Its additional
headers under `../../io_uring_interop` are project-maintained compatibility
bindings and are not part of the upstream source subset.

For a Mac-hosted Linux cross compile, pass Gradle
`-PuringLiburingBuildDir=/path/to/linux/build`. The
directory must contain the target's `src/liburing.a` and generated
`src/include/liburing/compat.h` and `io_uring_version.h`. Validation rejects an
incomplete directory; the Linux configure/build steps then use those existing
artifacts. The archive and sysroot must match the selected Linux architecture.
