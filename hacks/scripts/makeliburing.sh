~/bin/bash
set -x

#git refresh all submodules
git submodule foreach git pull origin master

#git submodule refresh liburing
cd liburing
git submodule update --init

cd $(dirname $0)/../../
pushd #gradle top dir

#locate ../../liburing from $0 dirname
LIBURING_DIR=$(dirname $0)/../../liburing

cd $LIBURING_DIR

sh configure --prefix=test/output/liburing
make -jl 8
make install
#install -D -m 644 include/liburing/io_uring.h test/output/liburing/include/liburing/io_uring.h
#install -D -m 644 include/liburing.h test/output/liburing/include/liburing.h
#install -D -m 644 include/liburing/compat.h test/output/liburing/include/liburing/compat.h
#install -D -m 644 include/liburing/barrier.h test/output/liburing/include/liburing/barrier.h
#install -D -m 644 include/liburing/io_uring_version.h test/output/liburing/include/liburing/io_uring_version.h
#install -D -m 644 liburing.a test/output/liburing/lib/liburing.a
#install -D -m 644 liburing-ffi.a test/output/liburing/lib/liburing-ffi.a
#install -D -m 755 liburing.so.2.4 test/output/liburing/lib/liburing.so.2.4
#install -D -m 755 liburing-ffi.so.2.4 test/output/liburing/lib/liburing-ffi.so.2.4

#we verify that the lib is in build.gradle.kts for linuxX64 target and that the lib is in the lib dir
grep -q "liburing.so" build.gradle.kts
if [ $? -ne 0 ]; then
    echo "liburing.so not found in build.gradle.kts"
#provide here-doc instructing what and where to insert for liburing cinterop inclusion in linuxX64 target
cat << EOF
    linuxX64("linuxX64") {
        binaries {
            executable {
                entryPoint = "main"
                cinterops {
                    //liburing
                    liburing("liburing") {
                        defFile = "$LIBURING_DIR/test/output/liburing/lib/liburing.def"
                        includeDirs = ["$LIBURING_DIR/test/output/liburing/include"]
                        compilerOpts = ["-I$LIBURING_DIR/test/output/liburing/include"]
                        linkerOpts = ["-L$LIBURING_DIR/test/output/liburing/lib"]
                    }
                }
            }
        }
    }
EOF
    exit 1
fi




fi



