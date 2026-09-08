#define _GNU_SOURCE
#include <jni.h>
#include <liburing.h>
#include <errno.h>
#include <fcntl.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <time.h>
#include <unistd.h>

/* One ring per UserspaceChannelBackend. No JVM process-wide ring or worker. */
struct jvm_uring {
    struct io_uring ring;
    struct io_uring_probe *probe;
    int failed;
    int exited;
};

static struct jvm_uring *ring_of(jlong handle) {
    return (struct jvm_uring *)(intptr_t)handle;
}

static int implemented(int op) {
    return op == 0 || op == 3 || op == 18 || op == 19 || op == 22 || op == 23 || op == 55;
}

JNIEXPORT jint JNICALL Java_borg_trikeshed_userspace_JvmUring_abiVersion(JNIEnv *env, jobject self) {
    (void)env; (void)self;
    return 1;
}

JNIEXPORT jlong JNICALL Java_borg_trikeshed_userspace_JvmUring_open(JNIEnv *env, jobject self, jint entries) {
    (void)env; (void)self;
    if (entries <= 0) return -EINVAL;
    struct jvm_uring *state = calloc(1, sizeof(*state));
    if (!state) return -ENOMEM;
    int result = io_uring_queue_init((unsigned)entries, &state->ring, 0);
    if (result < 0) { free(state); return result; }
    state->probe = io_uring_get_probe_ring(&state->ring);
    if (!state->probe) {
        result = errno ? -errno : -EOPNOTSUPP;
        io_uring_queue_exit(&state->ring);
        free(state);
        return result;
    }
    return (jlong)(intptr_t)state;
}

JNIEXPORT jboolean JNICALL Java_borg_trikeshed_userspace_JvmUring_supports(JNIEnv *env, jobject self, jlong handle, jint op) {
    (void)env; (void)self;
    struct jvm_uring *state = ring_of(handle);
    return state && !state->failed && implemented(op) && io_uring_opcode_supported(state->probe, op) ? JNI_TRUE : JNI_FALSE;
}

/* These primitives preserve io_uring result conventions, including EOF == 0. */
static int posix_execute(int op, int fd, void *bytes, unsigned len, jlong offset) {
    int result;
    switch (op) {
        case 0: return 0;
        case 3: result = fsync(fd); break;
        case 18: result = openat(fd, bytes, (int)offset, 0600); break;
        case 19: result = close(fd); break;
        case 22: result = offset == -1 ? (int)read(fd, bytes, len) : (int)pread(fd, bytes, len, offset); break;
        case 23: result = offset == -1 ? (int)write(fd, bytes, len) : (int)pwrite(fd, bytes, len, offset); break;
        case 55: result = ftruncate(fd, offset); break;
        default: return -EOPNOTSUPP;
    }
    return result < 0 ? -errno : result;
}

JNIEXPORT jint JNICALL Java_borg_trikeshed_userspace_JvmUring_execute(
    JNIEnv *env, jobject self, jlong handle, jint op, jint fd, jbyteArray array,
    jint start, jint len, jlong offset, jlong user_data) {
    (void)self;
    struct jvm_uring *state = ring_of(handle);
    if (!state || state->failed) return -EBADF;
    if (!implemented(op)) return -EOPNOTSUPP;
    int has_bytes = op == 18 || op == 22 || op == 23;
    if (has_bytes && (!array || start < 0 || len < 0 || (jlong)start + len > (*env)->GetArrayLength(env, array)))
        return -EINVAL;
    if ((op == 22 || op == 23) && offset < -1) return -EINVAL;
    if (op == 55 && offset < 0) return -EINVAL;
    if (op == 18 && (len == 0 || offset < 0 || (offset & ~(3L | 64L | 128L | 512L)) || (offset & 3) == 3))
        return -EINVAL;
    jbyte *elements = has_bytes ? (*env)->GetByteArrayElements(env, array, NULL) : NULL;
    if (has_bytes && !elements) return -ENOMEM;
    void *bytes = elements ? elements + start : NULL;
    char *path = NULL;
    int result = -ENOMEM;
    if (op == 18) {
        if (memchr(bytes, 0, len)) { result = -EINVAL; goto finish; }
        path = malloc((size_t)len + 1);
        if (!path) goto finish;
        memcpy(path, bytes, len);
        path[len] = 0;
        bytes = path;
    }
    if (!io_uring_opcode_supported(state->probe, op)) {
        result = posix_execute(op, fd, bytes, (unsigned)len, offset);
        goto finish;
    }
    struct io_uring_sqe *sqe = io_uring_get_sqe(&state->ring);
    if (!sqe) { result = -EBUSY; goto finish; }
    switch (op) {
        case 0: io_uring_prep_nop(sqe); break;
        case 3: io_uring_prep_fsync(sqe, fd, 0); break;
        case 18: io_uring_prep_openat(sqe, fd, bytes, (int)offset, 0600); break;
        case 19: io_uring_prep_close(sqe, fd); break;
        case 22: io_uring_prep_read(sqe, fd, bytes, (unsigned)len, (uint64_t)offset); break;
        case 23: io_uring_prep_write(sqe, fd, bytes, (unsigned)len, (uint64_t)offset); break;
        case 55:
            /* Numeric UAPI opcode permits builds with pre-ftruncate liburing headers. */
            io_uring_prep_rw(55, sqe, fd, NULL, 0, (uint64_t)offset);
            break;
    }
    io_uring_sqe_set_data64(sqe, (uint64_t)user_data);
    for (;;) {
        result = io_uring_submit(&state->ring);
        if (result >= 0) break;
        if (result == -EINTR) continue;
        if (op == 0) {
            /* The discovery NOP borrows no memory and owns no descriptor.
             * A denied enter can therefore reject the candidate safely. */
            io_uring_queue_exit(&state->ring);
            state->failed = state->exited = 1;
            goto finish;
        }
        /* Do not prepare/replay an SQE after an uncertain enter failure.
         * liburing resubmits only the original still-pending SQ tail. */
        state->failed = 1;
        struct timespec delay = { .tv_sec = 0, .tv_nsec = 100000000 };
        nanosleep(&delay, NULL);
    }
    struct io_uring_cqe *cqe;
    for (;;) {
        result = io_uring_wait_cqe(&state->ring, &cqe);
        if (result == -EINTR) continue;
        if (result < 0) {
            /* queue_exit only initiates cancellation. Keep the Java borrow until
             * the terminal CQE proves the kernel no longer uses its memory.
             * A permanently broken CQ intentionally cannot return ownership. */
            state->failed = 1;
            struct timespec delay = { .tv_sec = 0, .tv_nsec = 100000000 };
            nanosleep(&delay, NULL);
            continue;
        }
        if (io_uring_cqe_get_data64(cqe) != (uint64_t)user_data) {
            state->failed = 1;
            io_uring_cqe_seen(&state->ring, cqe);
            continue;
        }
        result = cqe->res;
        io_uring_cqe_seen(&state->ring, cqe);
        break;
    }
    if (state->failed) {
        io_uring_queue_exit(&state->ring);
        state->exited = 1;
    }
finish:
    free(path);
    if (elements) (*env)->ReleaseByteArrayElements(env, array, elements, op == 22 ? 0 : JNI_ABORT);
    return result;
}

JNIEXPORT void JNICALL Java_borg_trikeshed_userspace_JvmUring_close(JNIEnv *env, jobject self, jlong handle) {
    (void)env; (void)self;
    struct jvm_uring *state = ring_of(handle);
    if (!state) return;
    io_uring_free_probe(state->probe);
    if (!state->exited) io_uring_queue_exit(&state->ring);
    free(state);
}

JNIEXPORT jint JNICALL Java_borg_trikeshed_userspace_JvmUring_closeFd(JNIEnv *env, jobject self, jint fd) {
    (void)env; (void)self;
    return close(fd) == 0 ? 0 : -errno;
}

JNIEXPORT jlong JNICALL Java_borg_trikeshed_userspace_JvmUring_size(JNIEnv *env, jobject self, jint fd) {
    (void)env; (void)self;
    struct stat st;
    return fstat(fd, &st) == 0 ? st.st_size : -errno;
}
