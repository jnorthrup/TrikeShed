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
#include <limits.h>
#include <poll.h>
#include <pthread.h>
#include <sys/eventfd.h>
#include <unistd.h>

#if IO_URING_VERSION_MAJOR < 2 || (IO_URING_VERSION_MAJOR == 2 && IO_URING_VERSION_MINOR < 15)
#error "The JNI adapter requires liburing 2.15 or later"
#endif

struct jvm_request {
    struct jvm_request *next;
    uint64_t token, identity;
    int op, start, len;
    jbyteArray array;
    void *bytes;
};

/* One ring per UserspaceChannelBackend. No JVM process-wide ring or worker. */
struct jvm_uring {
    struct io_uring ring;
    struct io_uring_probe *probe;
    int failed;
    int exited;

    struct iovec *buffers;
    unsigned buffer_count;
    struct jvm_request *requests;
    uint64_t next_identity;
    int wake_fd, submit_error;
    pthread_mutex_t wait_lock;
    pthread_cond_t wait_closed;
    unsigned waiters;
    int closing;
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
        case IORING_OP_NOP: case IORING_OP_FSYNC: case IORING_OP_ASYNC_CANCEL:
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
    return 6;
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
    /* SUBMIT_ALL (5.18) keeps an issue error from stranding the rest of a batch. Coroutine submissions and OS
     * readiness waits may run on different JVM threads, so no issuer-affinity/taskrun or polling setup flags. */
    struct io_uring_params params;
    memset(&params, 0, sizeof(params));
    params.flags = IORING_SETUP_SUBMIT_ALL;
    int result = io_uring_queue_init_params((unsigned)entries, &state->ring, &params);
    if (result == -EINVAL) result = io_uring_queue_init((unsigned)entries, &state->ring, 0);
    if (result < 0) { free(state); return result; }
    state->wake_fd = eventfd(0, EFD_CLOEXEC | EFD_NONBLOCK | EFD_SEMAPHORE);
    if (state->wake_fd < 0) {
        result = -errno;
        io_uring_queue_exit(&state->ring);
        free(state);
        return result;
    }
    pthread_mutex_init(&state->wait_lock, NULL);
    pthread_cond_init(&state->wait_closed, NULL);
    state->probe = io_uring_get_probe_ring(&state->ring);
    if (!state->probe) {
        result = errno ? -errno : -EOPNOTSUPP;
        io_uring_queue_exit(&state->ring);
        close(state->wake_fd);
        pthread_cond_destroy(&state->wait_closed);
        pthread_mutex_destroy(&state->wait_lock);
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
    (void)self; (void)user_data;
    struct jvm_uring *state = ring_of(handle);
    if (!state || state->failed || state->exited) return -EBADF;
    if (!implemented(op) || op == IORING_OP_ASYNC_CANCEL) return -EOPNOTSUPP;
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
    /* This entry point is the POSIX fallback only; native SQEs always use submitBatch/reapBatch. */
    result = op == IORING_OP_STATX
        ? (statx(fd, bytes, (int)operation_flags, (unsigned)offset, metadata) == 0 ? 0 : -errno)
        : posix_execute(op, fd, bytes, (unsigned)len, offset, (unsigned)operation_flags);
finish:
    free(path);
    if (elements) (*env)->ReleaseByteArrayElements(env, array, elements, op == 22 || op == 21 ? 0 : JNI_ABORT);
    return result;
}

#define BATCH_FIELDS 8
#define BATCH_CHUNK 64
#define DEFERRED_RESULT INT_MIN

static void request_free(JNIEnv *env, struct jvm_request *request) {
    if (request->array) (*env)->DeleteGlobalRef(env, request->array);
    free(request->bytes);
    free(request);
}

/* A nonblocking kernel submission. Every admitted SQE owns a request until its original terminal CQE, including
 * after ASYNC_CANCEL; cancellation CQEs use data=0 and never release the original request's memory. */
JNIEXPORT jint JNICALL Java_borg_trikeshed_userspace_JvmUring_submitBatch(
    JNIEnv *env, jobject self, jlong handle, jint count, jlongArray control, jobjectArray arrays, jintArray results) {
    (void)self;
    struct jvm_uring *state = ring_of(handle);
    if (!state || state->failed || state->exited) return -EBADF;
    if (count < 0 || count > BATCH_CHUNK || !control || !arrays || !results ||
        (*env)->GetArrayLength(env, control) / BATCH_FIELDS < count ||
        (*env)->GetArrayLength(env, arrays) < count || (*env)->GetArrayLength(env, results) < count) return -EINVAL;
    jlong fields[BATCH_CHUNK * BATCH_FIELDS];
    jint res[BATCH_CHUNK];
    (*env)->GetLongArrayRegion(env, control, 0, count * BATCH_FIELDS, fields);
    for (jint i = 0; i < count; i++) {
        const jlong *f = fields + i * BATCH_FIELDS;
        int op = (int)f[0], fd = (int)f[1], start = (int)f[2], len = (int)f[3];
        jlong offset = f[4];
        int flags = (int)f[6], index = (int)(f[6] >> 32);
        res[i] = -EINVAL;
        if (!implemented(op) || !io_uring_opcode_supported(state->probe, op)) { res[i] = -EOPNOTSUPP; continue; }
        if (len < 0) continue;
        if (op == IORING_OP_FSYNC) {
            if ((unsigned)flags & ~IORING_FSYNC_DATASYNC) { res[i] = -EOPNOTSUPP; continue; }
        } else if (op == IORING_OP_STATX) {
            if ((unsigned)flags & ~(unsigned)(AT_SYMLINK_NOFOLLOW | AT_EMPTY_PATH | AT_NO_AUTOMOUNT)) continue;
        } else if (op != IORING_OP_MADVISE && op != IORING_OP_FADVISE && op != IORING_OP_OPENAT &&
                   op != IORING_OP_FALLOCATE && flags != 0) { res[i] = -EOPNOTSUPP; continue; }
        struct jvm_request *target = NULL;
        if (op == IORING_OP_ASYNC_CANCEL) {
            if (len != 0 || offset != 0) continue;
            for (target = state->requests; target && target->token != (uint64_t)f[5]; target = target->next) {}
            if (!target) { res[i] = -ENOENT; continue; }
        }
        int fixed = op == IORING_OP_READ_FIXED || op == IORING_OP_WRITE_FIXED;
        if ((fixed || op == IORING_OP_READ || op == IORING_OP_WRITE) && offset < -1) continue;
        if (fixed) {
            res[i] = -EFAULT;
            if (!state->buffers || index < 0 || (unsigned)index >= state->buffer_count) continue;
            const struct iovec *registered = &state->buffers[index];
            uintptr_t base = (uintptr_t)registered->iov_base, submitted = (uintptr_t)f[5];
            if (submitted < base || submitted - base > registered->iov_len ||
                (size_t)len > registered->iov_len - (submitted - base)) continue;
        }
        res[i] = -EINVAL;
        if (op == IORING_OP_FTRUNCATE && offset < 0) continue;
        if (op == IORING_OP_OPENAT && (len == 0 || offset < 0 || (offset & ~FACADE_OPEN_FLAGS) || (offset & 3) == 3)) continue;
        if (op == IORING_OP_STATX && (offset < 0 || offset > 0xffffffffL || f[5] != 0)) continue;
        if (!io_uring_sq_space_left(&state->ring)) { res[i] = -EAGAIN; continue; }
        jbyteArray array = (jbyteArray)(*env)->GetObjectArrayElement(env, arrays, i);
        size_t window = op == IORING_OP_STATX ? (size_t)len + 1 + sizeof(struct statx) : (size_t)len;
        int heap = op == IORING_OP_OPENAT || op == IORING_OP_STATX ||
            ((op == IORING_OP_READ || op == IORING_OP_WRITE) && array);
        if (heap && (!array || start < 0 || (uint64_t)start + window > (uint64_t)(*env)->GetArrayLength(env, array))) {
            if (array) (*env)->DeleteLocalRef(env, array);
            continue;
        }
        struct jvm_request *request = calloc(1, sizeof(*request));
        if (!request) { if (array) (*env)->DeleteLocalRef(env, array); res[i] = -ENOMEM; continue; }
        request->token = (uint64_t)f[7]; request->op = op; request->start = start; request->len = len;
        void *bytes = (void *)(uintptr_t)f[5];
        if (heap) {
            request->array = (*env)->NewGlobalRef(env, array);
            request->bytes = malloc(window + (op == IORING_OP_OPENAT ? 1 : 0) + 1);
            if (!request->array || !request->bytes) {
                request_free(env, request); (*env)->DeleteLocalRef(env, array);
                if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
                res[i] = -ENOMEM; continue;
            }
            bytes = request->bytes;
            if (op != IORING_OP_READ) (*env)->GetByteArrayRegion(env, array, start,
                op == IORING_OP_STATX ? len + 1 : len, bytes);
            if (op == IORING_OP_OPENAT) ((char *)bytes)[len] = 0;
            if ((op == IORING_OP_OPENAT || op == IORING_OP_STATX) &&
                (memchr(bytes, 0, len) || ((char *)bytes)[len] != 0)) {
                request_free(env, request); (*env)->DeleteLocalRef(env, array); continue;
            }
        }
        if (array) (*env)->DeleteLocalRef(env, array);
        struct io_uring_sqe *sqe = io_uring_get_sqe(&state->ring);
        if (!sqe) { request_free(env, request); res[i] = -EAGAIN; continue; }
        switch (op) {
            case IORING_OP_NOP: io_uring_prep_nop(sqe); break;
            case IORING_OP_FSYNC: io_uring_prep_fsync(sqe, fd, (unsigned)flags); break;
            case IORING_OP_ASYNC_CANCEL: io_uring_prep_cancel64(sqe, target->identity, 0); break;
            case IORING_OP_FALLOCATE: io_uring_prep_fallocate(sqe, fd, flags, offset, (uint64_t)len); break;
            case IORING_OP_READ_FIXED: io_uring_prep_read_fixed(sqe, fd, bytes, (unsigned)len, (uint64_t)offset, index); break;
            case IORING_OP_WRITE_FIXED: io_uring_prep_write_fixed(sqe, fd, bytes, (unsigned)len, (uint64_t)offset, index); break;
            case IORING_OP_OPENAT: io_uring_prep_openat(sqe, fd, bytes, open_flags(offset), (mode_t)flags); break;
            case IORING_OP_CLOSE: io_uring_prep_close(sqe, fd); break;
            case IORING_OP_STATX: io_uring_prep_statx(sqe, fd, bytes, flags, (unsigned)offset,
                (struct statx *)((char *)bytes + len + 1)); break;
            case IORING_OP_READ: io_uring_prep_read(sqe, fd, bytes, (unsigned)len, (uint64_t)offset); nowait_if_nonblocking(sqe, fd, offset); break;
            case IORING_OP_WRITE: io_uring_prep_write(sqe, fd, bytes, (unsigned)len, (uint64_t)offset); nowait_if_nonblocking(sqe, fd, offset); break;
            case IORING_OP_FADVISE: io_uring_prep_fadvise(sqe, fd, (uint64_t)offset, len, flags); break;
            case IORING_OP_MADVISE: io_uring_prep_madvise(sqe, bytes, (unsigned)len, flags); break;
            case IORING_OP_FTRUNCATE: io_uring_prep_ftruncate(sqe, fd, offset); break;
        }
        request->identity = ++state->next_identity;
        if (!request->identity) request->identity = ++state->next_identity;
        io_uring_sqe_set_data64(sqe, request->identity);
        request->next = state->requests; state->requests = request;
        res[i] = DEFERRED_RESULT;
    }
    int rc;
    do { rc = io_uring_submit(&state->ring); } while (rc == -EINTR);
    state->submit_error = rc < 0 ? rc : 0;
    if (rc < 0) {
        /* A discovery NOP owns no descriptor or external memory. A denied enter can reject that ring safely.
         * Other requests remain retained: an uncertain enter error is not their terminal completion. */
        int nops = 1;
        for (struct jvm_request *r = state->requests; r; r = r->next) if (r->op != IORING_OP_NOP) nops = 0;
        if (nops) { io_uring_queue_exit(&state->ring); state->failed = state->exited = 1; }
    }
    (*env)->SetIntArrayRegion(env, results, 0, count, res);
    return 0;
}

/* Reap many without waiting. A monotonic kernel identity prevents allocator reuse from retargeting an outstanding
 * cancellation; original user_data crosses back to commonMain, including zero and all-ones. */
JNIEXPORT jint JNICALL Java_borg_trikeshed_userspace_JvmUring_reapBatch(
    JNIEnv *env, jobject self, jlong handle, jlongArray tokens, jintArray results) {
    (void)self;
    struct jvm_uring *state = ring_of(handle);
    if (!state || !tokens || !results) return -EINVAL;
    jsize limit = (*env)->GetArrayLength(env, tokens);
    if ((*env)->GetArrayLength(env, results) < limit) limit = (*env)->GetArrayLength(env, results);
    if (limit > BATCH_CHUNK) limit = BATCH_CHUNK;
    jlong out_tokens[BATCH_CHUNK]; jint out_results[BATCH_CHUNK]; jint n = 0;
    if (state->exited) {
        while (state->requests && n < limit) {
            struct jvm_request *r = state->requests; state->requests = r->next;
            out_tokens[n] = (jlong)r->token; out_results[n++] = state->submit_error ? state->submit_error : -EBADF;
            request_free(env, r);
        }
    } else {
        if (io_uring_sq_ready(&state->ring)) {
            int rc; do { rc = io_uring_submit(&state->ring); } while (rc == -EINTR);
            state->submit_error = rc < 0 ? rc : 0;
        }
        /* peek_batch also enters the kernel when CQ overflow needs flushing. */
        struct io_uring_cqe *cqes[BATCH_CHUNK];
        unsigned ready = io_uring_peek_batch_cqe(&state->ring, cqes, (unsigned)limit);
        unsigned consumed = 0;
        for (unsigned i = 0; i < ready && n < limit; i++) {
            struct io_uring_cqe *cqe = cqes[i]; consumed++;
            uint64_t identity = io_uring_cqe_get_data64(cqe);
            if (!identity) continue; /* Internal cancellation requests have their own CQEs. */
            struct jvm_request **link = &state->requests;
            while (*link && (*link)->identity != identity) link = &(*link)->next;
            if (!*link) { state->failed = 1; continue; }
            struct jvm_request *r = *link;
            if (cqe->flags & IORING_CQE_F_MORE) continue;
            int result = cqe->res;
            if (r->array && result > 0 && r->op == IORING_OP_READ && result <= r->len)
                (*env)->SetByteArrayRegion(env, r->array, r->start, result, r->bytes);
            if (r->array && result == 0 && r->op == IORING_OP_STATX)
                (*env)->SetByteArrayRegion(env, r->array, r->start + r->len + 1, sizeof(struct statx),
                    (jbyte *)r->bytes + r->len + 1);
            out_tokens[n] = (jlong)r->token; out_results[n++] = result;
            *link = r->next; request_free(env, r);
        }
        io_uring_cq_advance(&state->ring, consumed);
    }
    (*env)->SetLongArrayRegion(env, tokens, 0, n, out_tokens);
    (*env)->SetIntArrayRegion(env, results, 0, n, out_results);
    /* Surface a failed enter without fabricating terminal CQEs or releasing the outstanding borrows. A later
     * reap may retry the original SQ tail; commonMain retains admission ownership across cleanup failure. */
    if (n == 0 && state->requests && state->failed && !state->exited) return -EIO;
    return n == 0 && state->requests && state->submit_error ? state->submit_error : n;
}

JNIEXPORT jint JNICALL Java_borg_trikeshed_userspace_JvmUring_cancel(
    JNIEnv *env, jobject self, jlong handle, jlong token, jboolean all) {
    (void)env; (void)self;
    struct jvm_uring *state = ring_of(handle);
    if (!state || state->exited) return -EBADF;
    if (!io_uring_opcode_supported(state->probe, IORING_OP_ASYNC_CANCEL)) return -EOPNOTSUPP;
    int prepared = 0;
    for (struct jvm_request *target = state->requests; target; target = target->next) {
        if (target->op == IORING_OP_ASYNC_CANCEL || (!all && target->token != (uint64_t)token)) continue;
        struct io_uring_sqe *sqe = io_uring_get_sqe(&state->ring);
        if (!sqe) break;
        io_uring_prep_cancel64(sqe, target->identity, 0);
        io_uring_sqe_set_data64(sqe, 0);
        prepared++;
        if (!all) break;
    }
    if (!prepared) return all ? -EAGAIN : -ENOENT;
    int rc; do { rc = io_uring_submit(&state->ring); } while (rc == -EINTR);
    return rc < 0 ? rc : 0;
}

static void wake_waiters(struct jvm_uring *state) {
    uint64_t count = state->waiters ? state->waiters : 1;
    ssize_t written;
    do { written = write(state->wake_fd, &count, sizeof(count)); } while (written < 0 && errno == EINTR);
}

JNIEXPORT void JNICALL Java_borg_trikeshed_userspace_JvmUring_wake(JNIEnv *env, jobject self, jlong handle) {
    (void)env; (void)self;
    struct jvm_uring *state = ring_of(handle);
    if (!state) return;
    pthread_mutex_lock(&state->wait_lock);
    wake_waiters(state);
    pthread_mutex_unlock(&state->wait_lock);
}

/* Readiness only: CQEs and their memory remain owned by reapBatch. The eventfd interrupts a blocked wait on
 * coroutine cancellation; close joins OS waiters before freeing the handle. Extra fds are emulated POLL/CONNECT. */
JNIEXPORT jint JNICALL Java_borg_trikeshed_userspace_JvmUring_await(
    JNIEnv *env, jobject self, jlong handle, jintArray descriptors, jintArray masks) {
    (void)self;
    struct jvm_uring *state = ring_of(handle);
    if (!state || !descriptors || !masks) return -EINVAL;
    jsize count = (*env)->GetArrayLength(env, descriptors);
    if ((*env)->GetArrayLength(env, masks) != count) return -EINVAL;
    struct pollfd *fds = calloc((size_t)count + 2, sizeof(*fds));
    jint *extra = count ? (*env)->GetIntArrayElements(env, descriptors, NULL) : NULL;
    jint *events = count ? (*env)->GetIntArrayElements(env, masks, NULL) : NULL;
    if (!fds || (count && (!extra || !events))) {
        if (extra) (*env)->ReleaseIntArrayElements(env, descriptors, extra, JNI_ABORT);
        if (events) (*env)->ReleaseIntArrayElements(env, masks, events, JNI_ABORT);
        free(fds); return -ENOMEM;
    }
    pthread_mutex_lock(&state->wait_lock);
    if (state->closing) {
        pthread_mutex_unlock(&state->wait_lock);
        if (extra) (*env)->ReleaseIntArrayElements(env, descriptors, extra, JNI_ABORT);
        if (events) (*env)->ReleaseIntArrayElements(env, masks, events, JNI_ABORT);
        free(fds); return -EBADF;
    }
    state->waiters++;
    fds[0].fd = state->exited ? -1 : state->ring.ring_fd; fds[0].events = POLLIN;
    fds[1].fd = state->wake_fd; fds[1].events = POLLIN;
    int immediate = state->exited;
    pthread_mutex_unlock(&state->wait_lock);
    for (jsize i = 0; i < count; i++) { fds[i + 2].fd = extra[i]; fds[i + 2].events = (short)events[i]; }
    if (extra) (*env)->ReleaseIntArrayElements(env, descriptors, extra, JNI_ABORT);
    if (events) (*env)->ReleaseIntArrayElements(env, masks, events, JNI_ABORT);
    int rc = immediate ? 0 : poll(fds, (nfds_t)count + 2, -1);
    int result = rc < 0 ? -errno : 0;
    if (fds[1].revents & POLLIN) {
        uint64_t value; ssize_t received;
        do { received = read(fds[1].fd, &value, sizeof(value)); } while (received < 0 && errno == EINTR);
    }
    free(fds);
    pthread_mutex_lock(&state->wait_lock);
    state->waiters--;
    pthread_cond_broadcast(&state->wait_closed);
    pthread_mutex_unlock(&state->wait_lock);
    return result;
}

JNIEXPORT jint JNICALL Java_borg_trikeshed_userspace_JvmUring_close(JNIEnv *env, jobject self, jlong handle) {
    (void)env; (void)self;
    struct jvm_uring *state = ring_of(handle);
    if (!state) return 0;
    /* Backend close first reaps every original request. Refuse to destroy retained native memory otherwise. */
    if (state->requests) return -EBUSY;
    pthread_mutex_lock(&state->wait_lock);
    state->closing = 1;
    wake_waiters(state);
    while (state->waiters) pthread_cond_wait(&state->wait_closed, &state->wait_lock);
    pthread_mutex_unlock(&state->wait_lock);
    io_uring_free_probe(state->probe);
    if (!state->exited) io_uring_queue_exit(&state->ring);
    close(state->wake_fd);
    pthread_cond_destroy(&state->wait_closed);
    pthread_mutex_destroy(&state->wait_lock);
    free(state->buffers);
    free(state);
    return 0;
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
