FROM eclipse-temurin@sha256:55f06b134812157418ea3bece155190112f73af2bbb010efa6059abd5b689231
RUN apt-get update && apt-get install -y --no-install-recommends build-essential git ca-certificates strace && rm -rf /var/lib/apt/lists/*
RUN git clone --depth 1 --branch liburing-2.15 https://github.com/axboe/liburing.git /tmp/liburing && \
    test "$(git -C /tmp/liburing rev-parse HEAD)" = d41bf9220ec39277ff235379e9089d9e0fd6c2a5 && \
    cd /tmp/liburing && ./configure --prefix=/opt/liburing && make -C src -j2 && make -C src install && rm -rf /tmp/liburing
COPY uring_jni.c /tmp/uring_jni.c
RUN mkdir -p /opt/trikeshed && gcc -O2 -fPIC -shared -I"$JAVA_HOME/include" -I"$JAVA_HOME/include/linux" -I/opt/liburing/include /tmp/uring_jni.c /opt/liburing/lib/liburing.a -o /opt/trikeshed/libtrikeshed_uring.so && rm /tmp/uring_jni.c
WORKDIR /results
