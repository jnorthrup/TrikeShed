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
    /* IORING_SETUP_SUBMIT_ALL: one enter submits every queued SQE, so it may wait for all of their CQEs. */
    int submit_all;
    struct iovec *buffers;
    unsigned buffer_count;
    /* executeBatch's heap-window staging: reused across batches and never cleared. A WRITE fills its slice before
     * submission; a READ copies out only the bytes its CQE reports. */
    unsigned char *staging;
    size_t staging_size;
};

/* io_uring retries a READ or WRITE of an O_NONBLOCK file that supports NOWAIT (a socket, a pipe, a terminal) by
 * polling it until it is ready, where read(2) and write(2) answer EAGAIN. At the stream position (offset -1) of such a
 * descriptor, RWF_NOWAIT gives the SQE that answer. A regular file or block device keeps its blocking read(2) meaning,
 * O_NONBLOCK or not. Costs fcntl, and fstat for an O_NONBLOCK descriptor, per stream READ or WRITE. */
#ifndef RWF_NOWAIT
#define RWF_NOWAIT 0x00000008
#endif
static void nowait_if_nonblocking(struct io_uring_sqe *sqe, int fd, long long offset) {
    struct stat st;
    int flags;
    if (offset != -1) return;
    flags = fcntl(fd, F_GETFL);
    if (flags < 0 || !(flags & O_NONBLOCK) || fstat(fd, &st) != 0) return;
    if (S_ISSOCK(st.st_mode) || S_ISFIFO(st.st_mode) || S_ISCHR(st.st_mode)) sqe->rw_flags |= RWF_NOWAIT;
}

static struct jvm_uring *ring_of(jlong handle) {
    return (struct jvm_uring *)(intptr_t)handle;
}

static int implemented(int op) {
    switch (op) {
        case IORING_OP_NOP: case IORING_OP_FSYNC:
        case IORING_OP_READ_FIXED: case IORING_OP_WRITE_FIXED:
        case IORING_OP_OPENAT: case IORING_OP_CLOSE:
        case IORING_OP_READ: case IORING_OP_WRITE: case IORING_OP_STATX:
        case IORING_OP_FADVISE: case IORING_OP_MADVISE:
        case IORING_OP_FTRUNCATE: case IORING_OP_FALLOCATE: return 1;
        default: return 0;
    }
}

JNIEXPORT jint JNICALL Java_borg_trikeshed_userspace_JvmUring_abiVersion(JNIEnv *env, jobject self) {
    (void)env; (void)self;
    return 5;
}

/* The facade's Linux asm-generic O_* values in this host's numbering (arm64 moves two bits). */
#define FACADE_OPEN_FLAGS (3L | 0x40L | 0x80L | 0x200L | 0x400L | 0x800L | 0x10000L | 0x20000L | 0x80000L)
static int open_flags(jlong flags) {
    return (int)(flags & 3) | (flags & 0x40 ? O_CREAT : 0) | (flags & 0x80 ? O_EXCL : 0) |
        (flags & 0x200 ? O_TRUNC : 0) | (flags & 0x400 ? O_APPEND : 0) | (flags & 0x800 ? O_NONBLOCK : 0) |
        (flags & 0x10000 ? O_DIRECTORY : 0) | (flags & 0x20000 ? O_NOFOLLOW : 0) | (flags & 0x80000 ? O_CLOEXEC : 0);
}

JNIEXPORT jlong JNICALL Java_borg_trikeshed_userspace_JvmUring_open(JNIEnv *env, jobject self, jint entries) {
    (void)env; (void)self;
    if (entries <= 0) return -EINVAL;
    struct jvm_uring *state = calloc(1, sizeof(*state));
    if (!state) return -ENOMEM;
    /* SUBMIT_ALL (5.18) keeps one failed SQE from stranding the rest of a batch; COOP_TASKRUN (5.19) completes
     * io-wq work at the waiter's next kernel entry instead of interrupting it. Older kernels refuse them: plain ring. */
    struct io_uring_params params;
    memset(&params, 0, sizeof(params));
    params.flags = IORING_SETUP_SUBMIT_ALL | IORING_SETUP_COOP_TASKRUN;
    int result = io_uring_queue_init_params((unsigned)entries, &state->ring, &params);
    if (result == 0) state->submit_all = 1;
    else if (result == -EINVAL) result = io_uring_queue_init((unsigned)entries, &state->ring, 0);
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
        case 17: result = fallocate(fd, (int)operation_flags, offset, len); break;
        case 18: result = openat(fd, bytes, open_flags(offset), (mode_t)operation_flags); break;
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
    } else if (op == IORING_OP_STATX) {
        if ((unsigned)operation_flags & ~(unsigned)(AT_SYMLINK_NOFOLLOW | AT_EMPTY_PATH | AT_NO_AUTOMOUNT)) return -EINVAL;
    } else if (op != IORING_OP_MADVISE && op != IORING_OP_FADVISE && op != IORING_OP_OPENAT &&
               op != IORING_OP_FALLOCATE && operation_flags != 0) {
        return -EOPNOTSUPP;
    }
    int fixed = op == IORING_OP_READ_FIXED || op == IORING_OP_WRITE_FIXED;
    int transfer = fixed || op == IORING_OP_READ || op == IORING_OP_WRITE;
    int has_bytes = op == IORING_OP_OPENAT || op == IORING_OP_STATX || ((op == IORING_OP_READ || op == IORING_OP_WRITE) && array);
    /* STATX: the path's len bytes, its NUL, then the struct statx the kernel writes. */
    jlong window = op == IORING_OP_STATX ? (jlong)len + 1 + (jlong)sizeof(struct statx) : len;
    if (op == IORING_OP_STATX && (!array || offset < 0 || offset > 0xffffffffL || address != 0)) return -EINVAL;
    if (has_bytes && (!array || start < 0 || len < 0 || (jlong)start + window > (*env)->GetArrayLength(env, array)))
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
    if (op == 18 && (len == 0 || offset < 0 || (offset & ~FACADE_OPEN_FLAGS) || (offset & 3) == 3))
        return -EINVAL;
    jbyte *elements = has_bytes ? (*env)->GetByteArrayElements(env, array, NULL) : NULL;
    if (has_bytes && !elements) return -ENOMEM;
    void *bytes = elements ? elements + start : (void *)(uintptr_t)address;
    char *path = NULL;
    struct statx *metadata = op == IORING_OP_STATX ? (struct statx *)((char *)bytes + len + 1) : NULL;
    int result = -ENOMEM;
    if (op == 18) {
        if (memchr(bytes, 0, len)) { result = -EINVAL; goto finish; }
        path = malloc((size_t)len + 1);
        if (!path) goto finish;
        memcpy(path, bytes, len);
        path[len] = 0;
        bytes = path;
    }
    if (op == IORING_OP_STATX && (memchr(bytes, 0, len) || ((char *)bytes)[len] != 0)) { result = -EINVAL; goto finish; }
    if (!io_uring_opcode_supported(state->probe, op)) {
        result = op == IORING_OP_STATX
            ? (statx(fd, bytes, (int)operation_flags, (unsigned)offset, metadata) == 0 ? 0 : -errno)
            : posix_execute(op, fd, bytes, (unsigned)len, offset, (unsigned)operation_flags);
        goto finish;
    }
    struct io_uring_sqe *sqe = io_uring_get_sqe(&state->ring);
    if (!sqe) { result = -EBUSY; goto finish; }
    switch (op) {
        case 0: io_uring_prep_nop(sqe); break;
        case 3: io_uring_prep_fsync(sqe, fd, (unsigned)operation_flags); break;
        case 17: io_uring_prep_fallocate(sqe, fd, operation_flags, offset, (uint64_t)len); break;
        case 4: io_uring_prep_read_fixed(sqe, fd, bytes, (unsigned)len, (uint64_t)offset, buffer_index); break;
        case 5: io_uring_prep_write_fixed(sqe, fd, bytes, (unsigned)len, (uint64_t)offset, buffer_index); break;
        case 18: io_uring_prep_openat(sqe, fd, bytes, open_flags(offset), (mode_t)operation_flags); break;
        case 19: io_uring_prep_close(sqe, fd); break;
        case 21: io_uring_prep_statx(sqe, fd, bytes, (int)operation_flags, (unsigned)offset, metadata); break;
        case 22: io_uring_prep_read(sqe, fd, bytes, (unsigned)len, (uint64_t)offset); nowait_if_nonblocking(sqe, fd, offset); break;
        case 23: io_uring_prep_write(sqe, fd, bytes, (unsigned)len, (uint64_t)offset); nowait_if_nonblocking(sqe, fd, offset); break;
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
    if (elements) (*env)->ReleaseByteArrayElements(env, array, elements, op == 22 || op == 21 ? 0 : JNI_ABORT);
    return result;
}

#define BATCH_FIELDS 8
#define BATCH_CHUNK 64

/* The operations executeBatch carries: data-path ops whose memory is a heap window, a registered buffer or an
 * address. Path, descriptor-lifecycle and metadata ops keep the one-SQE execute. */
static int batched(int op) {
    switch (op) {
        case IORING_OP_NOP: case IORING_OP_FSYNC: case IORING_OP_READ_FIXED: case IORING_OP_WRITE_FIXED:
        case IORING_OP_FALLOCATE: case IORING_OP_READ: case IORING_OP_WRITE: case IORING_OP_FADVISE:
        case IORING_OP_MADVISE: case IORING_OP_FTRUNCATE: return 1;
        default: return 0;
    }
}

/* execute's argument checks for the batched operations: 0, or the CQE result the op completes with unsubmitted. */
static int batch_invalid(struct jvm_uring *state, int op, jint len, jlong offset, jint flags, jint index, jlong address) {
    if (!batched(op) || !io_uring_opcode_supported(state->probe, op)) return -EOPNOTSUPP;
    if (len < 0) return -EINVAL;
    if (op == IORING_OP_FSYNC) {
        if ((unsigned)flags & ~IORING_FSYNC_DATASYNC) return -EOPNOTSUPP;
    } else if (op != IORING_OP_MADVISE && op != IORING_OP_FADVISE && op != IORING_OP_FALLOCATE && flags != 0) {
        return -EOPNOTSUPP;
    }
    int fixed = op == IORING_OP_READ_FIXED || op == IORING_OP_WRITE_FIXED;
    if ((fixed || op == IORING_OP_READ || op == IORING_OP_WRITE) && offset < -1) return -EINVAL;
    if (fixed) {
        if (!state->buffers || index < 0 || (unsigned)index >= state->buffer_count) return -EFAULT;
        const struct iovec *registered = &state->buffers[index];
        uintptr_t base = (uintptr_t)registered->iov_base;
        uintptr_t submitted = (uintptr_t)address;
        if (submitted < base || submitted - base > registered->iov_len ||
            (size_t)len > registered->iov_len - (submitted - base)) return -EFAULT;
    }
    if (op == IORING_OP_FTRUNCATE && offset < 0) return -EINVAL;
    return 0;
}

/* Grows the staging to [need] bytes: a fresh block, since nothing staged outlives a batch. */
static int stage(struct jvm_uring *state, size_t need) {
    if (need <= state->staging_size) return 0;
    size_t size = state->staging_size ? state->staging_size : 65536;
    while (size < need) size *= 2;
    unsigned char *grown = malloc(size);
    if (!grown) return -ENOMEM;
    free(state->staging);
    state->staging = grown;
    state->staging_size = size;
    return 0;
}

/* Up to BATCH_CHUNK SQEs (and no more than the SQ holds) go to the kernel in one enter, which also waits for their
 * CQEs. A heap window travels through the ring's staging: only [start, start + len) is copied in for a WRITE, and
 * only the res bytes a READ completed are copied back, so the caller's bytes past res stay its own. */
JNIEXPORT jint JNICALL Java_borg_trikeshed_userspace_JvmUring_executeBatch(
    JNIEnv *env, jobject self, jlong handle, jint count, jlongArray control, jobjectArray arrays, jintArray results) {
    (void)self;
    struct jvm_uring *state = ring_of(handle);
    if (!state || state->failed || state->exited) return -EBADF;
    if (count < 0 || !control || !arrays || !results ||
        (*env)->GetArrayLength(env, control) / BATCH_FIELDS < count ||
        (*env)->GetArrayLength(env, arrays) < count || (*env)->GetArrayLength(env, results) < count) return -EINVAL;
    jint limit = (jint)state->ring.sq.ring_entries < BATCH_CHUNK ? (jint)state->ring.sq.ring_entries : BATCH_CHUNK;
    for (jint base = 0; base < count; ) {
        jint n = count - base < limit ? count - base : limit;
        jlong fields[BATCH_CHUNK * BATCH_FIELDS];
        jint res[BATCH_CHUNK];
        size_t slice[BATCH_CHUNK];
        unsigned char done[BATCH_CHUNK];
        if (state->failed || state->exited) {
            for (jint i = 0; i < n; i++) res[i] = -EBADF;
            (*env)->SetIntArrayRegion(env, results, base, n, res);
            base += n;
            continue;
        }
        (*env)->GetLongArrayRegion(env, control, base * BATCH_FIELDS, n * BATCH_FIELDS, fields);
        size_t need = 0;
        int borrows = 0;
        for (jint i = 0; i < n; i++) {
            const jlong *f = fields + i * BATCH_FIELDS;
            int op = (int)f[0];
            jint start = (jint)f[2], len = (jint)f[3];
            slice[i] = SIZE_MAX;
            done[i] = 1;
            res[i] = batch_invalid(state, op, len, f[4], (jint)f[6], (jint)(f[6] >> 32), f[5]);
            if (res[i]) continue;
            if (op == IORING_OP_READ || op == IORING_OP_WRITE) {
                jbyteArray array = (jbyteArray)(*env)->GetObjectArrayElement(env, arrays, base + i);
                if (array) {
                    jsize size = (*env)->GetArrayLength(env, array);
                    (*env)->DeleteLocalRef(env, array);
                    if (start < 0 || (jlong)start + len > size) { res[i] = -EINVAL; continue; }
                    slice[i] = need;
                    need += ((size_t)len + 63) & ~(size_t)63;
                }
            }
            if (op != IORING_OP_NOP) borrows = 1;
            done[i] = 0;
        }
        if (need && stage(state, need)) {
            for (jint i = 0; i < n; i++) if (!done[i]) { res[i] = -ENOMEM; done[i] = 1; }
        }
        int prepared = 0;
        for (jint i = 0; i < n; i++) {
            if (done[i]) continue;
            const jlong *f = fields + i * BATCH_FIELDS;
            int op = (int)f[0], fd = (int)f[1];
            jint start = (jint)f[2], len = (jint)f[3];
            jlong offset = f[4];
            int flags = (int)f[6], index = (int)(f[6] >> 32);
            void *bytes = (void *)(uintptr_t)f[5];
            if (slice[i] != SIZE_MAX) {
                bytes = state->staging + slice[i];
                if (op == IORING_OP_WRITE) {
                    jbyteArray array = (jbyteArray)(*env)->GetObjectArrayElement(env, arrays, base + i);
                    (*env)->GetByteArrayRegion(env, array, start, len, (jbyte *)bytes);
                    (*env)->DeleteLocalRef(env, array);
                }
            }
            /* The SQ is empty between calls and n <= its entries. */
            struct io_uring_sqe *sqe = io_uring_get_sqe(&state->ring);
            if (!sqe) { res[i] = -EBUSY; done[i] = 1; continue; }
            switch (op) {
                case IORING_OP_NOP: io_uring_prep_nop(sqe); break;
                case IORING_OP_FSYNC: io_uring_prep_fsync(sqe, fd, (unsigned)flags); break;
                case IORING_OP_FALLOCATE: io_uring_prep_fallocate(sqe, fd, flags, offset, (uint64_t)len); break;
                case IORING_OP_READ_FIXED: io_uring_prep_read_fixed(sqe, fd, bytes, (unsigned)len, (uint64_t)offset, index); break;
                case IORING_OP_WRITE_FIXED: io_uring_prep_write_fixed(sqe, fd, bytes, (unsigned)len, (uint64_t)offset, index); break;
                case IORING_OP_READ: io_uring_prep_read(sqe, fd, bytes, (unsigned)len, (uint64_t)offset); nowait_if_nonblocking(sqe, fd, offset); break;
                case IORING_OP_WRITE: io_uring_prep_write(sqe, fd, bytes, (unsigned)len, (uint64_t)offset); nowait_if_nonblocking(sqe, fd, offset); break;
                case IORING_OP_FADVISE: io_uring_prep_fadvise(sqe, fd, (uint64_t)offset, len, flags); break;
                case IORING_OP_MADVISE: io_uring_prep_madvise(sqe, bytes, (unsigned)len, flags); break;
                case IORING_OP_FTRUNCATE: io_uring_prep_ftruncate(sqe, fd, offset); break;
            }
            io_uring_sqe_set_data64(sqe, (uint64_t)i);
            prepared++;
        }
        int reaped = 0;
        while (reaped < prepared) {
            /* Without SUBMIT_ALL an SQE that fails to issue leaves its successors queued for the next enter, so
             * that ring waits for one CQE at a time. */
            int rc = io_uring_submit_and_wait(&state->ring, state->submit_all ? (unsigned)(prepared - reaped) : 1);
            if (rc < 0 && rc != -EINTR) {
                if (!borrows) {
                    /* NOPs borrow no memory and own no descriptor: a refused enter can fail them safely. */
                    io_uring_queue_exit(&state->ring);
                    state->failed = state->exited = 1;
                    for (jint i = 0; i < n; i++) if (!done[i]) { res[i] = rc; done[i] = 1; }
                    break;
                }
                /* Never return a borrow after an uncertain enter failure: liburing resubmits only the still-pending
                 * SQ tail, and the CQ is drained until every prepared SQE reports. */
                state->failed = 1;
                struct timespec delay = { .tv_sec = 0, .tv_nsec = 100000000 };
                nanosleep(&delay, NULL);
            }
            unsigned head, seen = 0;
            struct io_uring_cqe *cqe;
            io_uring_for_each_cqe(&state->ring, head, cqe) {
                uint64_t i = io_uring_cqe_get_data64(cqe);
                if (i < (uint64_t)n && !done[i]) { res[i] = cqe->res; done[i] = 1; reaped++; }
                else state->failed = 1;
                seen++;
            }
            io_uring_cq_advance(&state->ring, seen);
        }
        for (jint i = 0; i < n; i++) {
            const jlong *f = fields + i * BATCH_FIELDS;
            if ((int)f[0] == IORING_OP_READ && slice[i] != SIZE_MAX && res[i] > 0 && res[i] <= (jint)f[3]) {
                jbyteArray array = (jbyteArray)(*env)->GetObjectArrayElement(env, arrays, base + i);
                (*env)->SetByteArrayRegion(env, array, (jint)f[2], res[i], (const jbyte *)(state->staging + slice[i]));
                (*env)->DeleteLocalRef(env, array);
            }
        }
        (*env)->SetIntArrayRegion(env, results, base, n, res);
        if (state->failed && !state->exited) {
            io_uring_queue_exit(&state->ring);
            state->exited = 1;
        }
        base += n;
    }
    return 0;
}

JNIEXPORT void JNICALL Java_borg_trikeshed_userspace_JvmUring_close(JNIEnv *env, jobject self, jlong handle) {
    (void)env; (void)self;
    struct jvm_uring *state = ring_of(handle);
    if (!state) return;
    io_uring_free_probe(state->probe);
    if (!state->exited) io_uring_queue_exit(&state->ring);
    free(state->buffers);
    free(state->staging);
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
