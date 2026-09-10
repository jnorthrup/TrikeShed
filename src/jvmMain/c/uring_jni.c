#define _GNU_SOURCE
#include <jni.h>
#include <liburing.h>
#include <errno.h>
#include <fcntl.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <time.h>
#include <unistd.h>

#if IO_URING_VERSION_MAJOR < 2 || (IO_URING_VERSION_MAJOR == 2 && IO_URING_VERSION_MINOR < 15)
#error "The JNI adapter requires liburing 2.15 or later"
#endif

/* One ring per UserspaceChannelBackend. No JVM process-wide ring or worker. */
struct jvm_uring {
    struct io_uring ring;
    struct io_uring_probe *probe;
    int failed;
    int exited;
    struct iovec *buffers;
    unsigned buffer_count;
};

static struct jvm_uring *ring_of(jlong handle) {
    return (struct jvm_uring *)(intptr_t)handle;
}

static int implemented(int op) {
    switch (op) {
        case IORING_OP_NOP: case IORING_OP_FSYNC:
        case IORING_OP_READ_FIXED: case IORING_OP_WRITE_FIXED:
        case IORING_OP_OPENAT: case IORING_OP_CLOSE:
        case IORING_OP_READ: case IORING_OP_WRITE:
        case IORING_OP_FADVISE: case IORING_OP_MADVISE:
        case IORING_OP_FTRUNCATE: return 1;
        default: return 0;
    }
}

JNIEXPORT jint JNICALL Java_borg_trikeshed_userspace_JvmUring_abiVersion(JNIEnv *env, jobject self) {
    (void)env; (void)self;
    return 2;
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

JNIEXPORT jint JNICALL Java_borg_trikeshed_userspace_JvmUring_registerBuffers(
    JNIEnv *env, jobject self, jlong handle, jlongArray addresses, jlongArray lengths) {
    (void)self;
    struct jvm_uring *state = ring_of(handle);
    if (!state || state->failed || state->exited) return -EBADF;
    if (state->buffers) return -EBUSY;
    if (!addresses || !lengths) return -EINVAL;
    jsize count = (*env)->GetArrayLength(env, addresses);
    if (count <= 0 || count != (*env)->GetArrayLength(env, lengths)) return -EINVAL;
    struct iovec *buffers = calloc((size_t)count, sizeof(*buffers));
    if (!buffers) return -ENOMEM;
    jlong *bases = (*env)->GetLongArrayElements(env, addresses, NULL);
    jlong *sizes = (*env)->GetLongArrayElements(env, lengths, NULL);
    int result = -ENOMEM;
    if (!bases || !sizes) goto finish;
    for (jsize i = 0; i < count; i++) {
        if (bases[i] < 0 || sizes[i] <= 0 || (uint64_t)sizes[i] > SIZE_MAX - (uintptr_t)bases[i]) {
            result = -EINVAL;
            goto finish;
        }
        buffers[i].iov_base = (void *)(uintptr_t)bases[i];
        buffers[i].iov_len = (size_t)sizes[i];
    }
    result = io_uring_register_buffers(&state->ring, buffers, (unsigned)count);
    if (result == 0) {
        state->buffers = buffers;
        state->buffer_count = (unsigned)count;
        buffers = NULL;
    }
finish:
    if (bases) (*env)->ReleaseLongArrayElements(env, addresses, bases, JNI_ABORT);
    if (sizes) (*env)->ReleaseLongArrayElements(env, lengths, sizes, JNI_ABORT);
    free(buffers);
    return result;
}

JNIEXPORT jint JNICALL Java_borg_trikeshed_userspace_JvmUring_unregisterBuffers(
    JNIEnv *env, jobject self, jlong handle) {
    (void)env; (void)self;
    struct jvm_uring *state = ring_of(handle);
    if (!state || state->exited) return -EBADF;
    if (!state->buffers) return -ENXIO;
    int result = io_uring_unregister_buffers(&state->ring);
    if (result == 0) {
        free(state->buffers);
        state->buffers = NULL;
        state->buffer_count = 0;
    }
    return result;
}

/* These primitives preserve io_uring result conventions, including EOF == 0. */
static int posix_execute(int op, int fd, void *bytes, unsigned len, jlong offset, unsigned operation_flags) {
    int result;
    switch (op) {
        case 0: return 0;
        case 3: result = operation_flags & IORING_FSYNC_DATASYNC ? fdatasync(fd) : fsync(fd); break;
        case 18: result = openat(fd, bytes, (int)offset, (mode_t)operation_flags); break;
        case 19: result = close(fd); break;
        case 4: case 22: result = offset == -1 ? (int)read(fd, bytes, len) : (int)pread(fd, bytes, len, offset); break;
        case 5: case 23: result = offset == -1 ? (int)write(fd, bytes, len) : (int)pwrite(fd, bytes, len, offset); break;
        case 24: return -posix_fadvise(fd, offset, len, (int)operation_flags);
        case 25: result = madvise(bytes, len, (int)operation_flags); break;
        case 55: result = ftruncate(fd, offset); break;
        default: return -EOPNOTSUPP;
    }
    return result < 0 ? -errno : result;
}

JNIEXPORT jint JNICALL Java_borg_trikeshed_userspace_JvmUring_execute(
    JNIEnv *env, jobject self, jlong handle, jint op, jint fd, jbyteArray array,
    jint start, jint len, jlong offset, jlong user_data, jlong address,
    jint operation_flags, jint buffer_index) {
    (void)self;
    struct jvm_uring *state = ring_of(handle);
    if (!state || state->failed || state->exited) return -EBADF;
    if (!implemented(op)) return -EOPNOTSUPP;
    if (len < 0) return -EINVAL;
    if (op == IORING_OP_FSYNC) {
        if ((unsigned)operation_flags & ~IORING_FSYNC_DATASYNC) return -EOPNOTSUPP;
    } else if (op != IORING_OP_MADVISE && op != IORING_OP_FADVISE && op != IORING_OP_OPENAT && operation_flags != 0) {
        return -EOPNOTSUPP;
    }
    int fixed = op == IORING_OP_READ_FIXED || op == IORING_OP_WRITE_FIXED;
    int transfer = fixed || op == IORING_OP_READ || op == IORING_OP_WRITE;
    int has_bytes = op == IORING_OP_OPENAT || ((op == IORING_OP_READ || op == IORING_OP_WRITE) && array);
    if (has_bytes && (!array || start < 0 || len < 0 || (jlong)start + len > (*env)->GetArrayLength(env, array)))
        return -EINVAL;
    if (transfer && offset < -1) return -EINVAL;
    if (fixed) {
        if (!state->buffers || buffer_index < 0 || (unsigned)buffer_index >= state->buffer_count) return -EFAULT;
        const struct iovec *registered = &state->buffers[buffer_index];
        uintptr_t base = (uintptr_t)registered->iov_base;
        uintptr_t submitted = (uintptr_t)address;
        if (submitted < base || submitted - base > registered->iov_len ||
            (size_t)len > registered->iov_len - (submitted - base)) return -EFAULT;
    }
    if (op == 55 && offset < 0) return -EINVAL;
    if (op == 18 && (len == 0 || offset < 0 || (offset & ~(3L | 64L | 128L | 512L | 1024L)) || (offset & 3) == 3))
        return -EINVAL;
    jbyte *elements = has_bytes ? (*env)->GetByteArrayElements(env, array, NULL) : NULL;
    if (has_bytes && !elements) return -ENOMEM;
    void *bytes = elements ? elements + start : (void *)(uintptr_t)address;
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
        result = posix_execute(op, fd, bytes, (unsigned)len, offset, (unsigned)operation_flags);
        goto finish;
    }
    struct io_uring_sqe *sqe = io_uring_get_sqe(&state->ring);
    if (!sqe) { result = -EBUSY; goto finish; }
    switch (op) {
        case 0: io_uring_prep_nop(sqe); break;
        case 3: io_uring_prep_fsync(sqe, fd, (unsigned)operation_flags); break;
        case 4: io_uring_prep_read_fixed(sqe, fd, bytes, (unsigned)len, (uint64_t)offset, buffer_index); break;
        case 5: io_uring_prep_write_fixed(sqe, fd, bytes, (unsigned)len, (uint64_t)offset, buffer_index); break;
        case 18: io_uring_prep_openat(sqe, fd, bytes, (int)offset, (mode_t)operation_flags); break;
        case 19: io_uring_prep_close(sqe, fd); break;
        case 22: io_uring_prep_read(sqe, fd, bytes, (unsigned)len, (uint64_t)offset); break;
        case 23: io_uring_prep_write(sqe, fd, bytes, (unsigned)len, (uint64_t)offset); break;
        case 24: io_uring_prep_fadvise(sqe, fd, (uint64_t)offset, len, operation_flags); break;
        case 25: io_uring_prep_madvise(sqe, bytes, (unsigned)len, operation_flags); break;
        case 55: io_uring_prep_ftruncate(sqe, fd, offset); break;
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
    free(state->buffers);
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
