#include <node_api.h>
#include <liburing.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>

#if IO_URING_VERSION_MAJOR < 2 || (IO_URING_VERSION_MAJOR == 2 && IO_URING_VERSION_MINOR < 15)
#error "The Node adapter requires the vendored liburing 2.15 or later"
#endif

/* Handles are per-environment identities, never unchecked caller pointers. */
struct node_ring {
    uint64_t id;
    struct io_uring ring;
    struct io_uring_probe *probe;
    struct node_ring *next;
    bool failed;
    bool exited;
};

struct node_uring {
    uint64_t next_id;
    struct node_ring *rings;
};

static napi_value integer(napi_env env, int32_t value) {
    napi_value result;
    return napi_create_int32(env, value, &result) == napi_ok ? result : NULL;
}

static napi_value bigint(napi_env env, int64_t value) {
    napi_value result;
    return napi_create_bigint_int64(env, value, &result) == napi_ok ? result : NULL;
}

static napi_value string(napi_env env, const char *value) {
    napi_value result;
    return napi_create_string_utf8(env, value, NAPI_AUTO_LENGTH, &result) == napi_ok ? result : NULL;
}

static bool int32(napi_env env, napi_value value, int32_t *result) {
    double number;
    if (napi_get_value_double(env, value, &number) != napi_ok ||
        !(number >= INT32_MIN && number <= INT32_MAX)) return false;
    *result = (int32_t)number;
    return number == *result;
}

static bool int64(napi_env env, napi_value value, int64_t *result) {
    bool lossless;
    return napi_get_value_bigint_int64(env, value, result, &lossless) == napi_ok && lossless;
}

static struct node_uring *context(napi_env env) {
    struct node_uring *state = NULL;
    if (napi_get_instance_data(env, (void **)&state) != napi_ok) return NULL;
    return state;
}

static struct node_ring *ring_of(napi_env env, napi_value handle) {
    struct node_uring *state = context(env);
    int64_t id;
    if (!state || !int64(env, handle, &id) || id <= 0) return NULL;
    for (struct node_ring *ring = state->rings; ring; ring = ring->next)
        if (ring->id == (uint64_t)id) return ring;
    return NULL;
}

static bool implemented(int op) {
    switch (op) {
        case IORING_OP_NOP: case IORING_OP_OPENAT: case IORING_OP_CLOSE:
        case IORING_OP_READ: case IORING_OP_WRITE: case IORING_OP_FSYNC:
        case IORING_OP_FTRUNCATE: return true;
        default: return false;
    }
}

static napi_value abi_version(napi_env env, napi_callback_info info) {
    (void)info;
    return integer(env, 1);
}

static napi_value platform(napi_env env, napi_callback_info info) {
    (void)info;
    return string(env, "linux");
}

static napi_value architecture(napi_env env, napi_callback_info info) {
    (void)info;
#if defined(__aarch64__)
    return string(env, "arm64");
#elif defined(__x86_64__)
    return string(env, "x86_64");
#else
#error "The Node io_uring adapter supports Linux arm64 and x86_64"
#endif
}

static napi_value napi_version(napi_env env, napi_callback_info info) {
    (void)info;
    return integer(env, NAPI_VERSION);
}

static napi_value ring_open(napi_env env, napi_callback_info info) {
    napi_value args[1];
    size_t count = 1;
    int32_t entries;
    if (napi_get_cb_info(env, info, &count, args, NULL, NULL) != napi_ok || count != 1 ||
        !int32(env, args[0], &entries) || entries <= 0) return bigint(env, -EINVAL);
    struct node_uring *state = context(env);
    if (!state || state->next_id == INT64_MAX) return bigint(env, -EMFILE);
    struct node_ring *ring = calloc(1, sizeof(*ring));
    if (!ring) return bigint(env, -ENOMEM);
    int result = io_uring_queue_init((unsigned)entries, &ring->ring, 0);
    if (result < 0) { free(ring); return bigint(env, result); }
    ring->probe = io_uring_get_probe_ring(&ring->ring);
    if (!ring->probe) {
        result = errno ? -errno : -EOPNOTSUPP;
        io_uring_queue_exit(&ring->ring);
        free(ring);
        return bigint(env, result);
    }
    ring->id = ++state->next_id;
    ring->next = state->rings;
    state->rings = ring;
    return bigint(env, (int64_t)ring->id);
}

static napi_value supports(napi_env env, napi_callback_info info) {
    napi_value args[2], result;
    size_t count = 2;
    int32_t op;
    bool supported = false;
    if (napi_get_cb_info(env, info, &count, args, NULL, NULL) == napi_ok && count == 2 &&
        int32(env, args[1], &op)) {
        struct node_ring *ring = ring_of(env, args[0]);
        supported = ring && !ring->failed && !ring->exited && implemented(op) &&
            io_uring_opcode_supported(ring->probe, op);
    }
    return napi_get_boolean(env, supported, &result) == napi_ok ? result : NULL;
}

static int posix_execute(int op, int fd, void *bytes, unsigned len, int64_t offset) {
    int result;
    switch (op) {
        case IORING_OP_NOP: return 0;
        case IORING_OP_OPENAT: result = openat(fd, bytes, (int)offset, 0666); break;
        case IORING_OP_CLOSE: result = close(fd); break;
        case IORING_OP_READ: result = offset == -1 ? (int)read(fd, bytes, len) : (int)pread(fd, bytes, len, offset); break;
        case IORING_OP_WRITE: result = offset == -1 ? (int)write(fd, bytes, len) : (int)pwrite(fd, bytes, len, offset); break;
        case IORING_OP_FSYNC: result = fsync(fd); break;
        case IORING_OP_FTRUNCATE: result = ftruncate(fd, offset); break;
        default: return -EOPNOTSUPP;
    }
    return result < 0 ? -errno : result;
}

/* ABI1 borrows Int8Array bytes synchronously until the matching terminal CQE.
 * Kernel-unsupported opcodes execute the POSIX effect against the same fd.
 */
static napi_value execute(napi_env env, napi_callback_info info) {
    napi_value args[8];
    size_t count = 8;
    int32_t op, fd, start, length;
    int64_t offset, user_data;
    if (napi_get_cb_info(env, info, &count, args, NULL, NULL) != napi_ok || count != 8 ||
        !int32(env, args[1], &op) || !int32(env, args[2], &fd) ||
        !int32(env, args[4], &start) || !int32(env, args[5], &length) ||
        !int64(env, args[6], &offset) || !int64(env, args[7], &user_data)) return integer(env, -EINVAL);
    struct node_ring *ring = ring_of(env, args[0]);
    if (!ring || ring->failed || ring->exited) return integer(env, -EBADF);
    if (!implemented(op)) return integer(env, -EOPNOTSUPP);
    if (start < 0 || length < 0) return integer(env, -EINVAL);
    bool transfer = op == IORING_OP_READ || op == IORING_OP_WRITE;
    void *bytes = NULL;
    if (transfer || op == IORING_OP_OPENAT) {
        napi_typedarray_type type;
        size_t capacity, byte_offset;
        napi_value arraybuffer;
        if (napi_get_typedarray_info(env, args[3], &type, &capacity, &bytes, &arraybuffer, &byte_offset) != napi_ok ||
            (type != napi_int8_array && type != napi_uint8_array) ||
            (size_t)start > capacity || (size_t)length > capacity - (size_t)start)
            return integer(env, -EINVAL);
        if (bytes) bytes = (unsigned char *)bytes + start;
    }
    if (transfer && offset < -1) return integer(env, -EINVAL);
    if (op == IORING_OP_FTRUNCATE && offset < 0) return integer(env, -EINVAL);
    char *path = NULL;
    if (op == IORING_OP_OPENAT) {
        if (fd != AT_FDCWD || length == 0 || offset < 0 ||
            (offset & ~(3LL | O_CREAT | O_EXCL | O_TRUNC)) || (offset & 3) == 3 ||
            memchr(bytes, 0, (size_t)length)) return integer(env, -EINVAL);
        path = malloc((size_t)length + 1);
        if (!path) return integer(env, -ENOMEM);
        memcpy(path, bytes, (size_t)length);
        path[length] = 0;
        bytes = path;
    }
    int result;
    if (!io_uring_opcode_supported(ring->probe, op)) {
        result = posix_execute(op, fd, bytes, (unsigned)length, offset);
        goto finish;
    }
    struct io_uring_sqe *sqe = io_uring_get_sqe(&ring->ring);
    if (!sqe) { result = -EBUSY; goto finish; }
    switch (op) {
        case IORING_OP_NOP: io_uring_prep_nop(sqe); break;
        case IORING_OP_OPENAT: io_uring_prep_openat(sqe, fd, bytes, (int)offset, 0666); break;
        case IORING_OP_CLOSE: io_uring_prep_close(sqe, fd); break;
        case IORING_OP_READ: io_uring_prep_read(sqe, fd, bytes, (unsigned)length, (uint64_t)offset); break;
        case IORING_OP_WRITE: io_uring_prep_write(sqe, fd, bytes, (unsigned)length, (uint64_t)offset); break;
        case IORING_OP_FSYNC: io_uring_prep_fsync(sqe, fd, 0); break;
        case IORING_OP_FTRUNCATE: io_uring_prep_ftruncate(sqe, fd, offset); break;
    }
    io_uring_sqe_set_data64(sqe, (uint64_t)user_data);
    for (;;) {
        result = io_uring_submit(&ring->ring);
        if (result >= 0) break;
        if (result == -EINTR) continue;
        if (op == IORING_OP_NOP) {
            io_uring_queue_exit(&ring->ring);
            ring->failed = ring->exited = true;
            goto finish;
        }
        /* Never replay an admitted effect or release its borrow after an
         * uncertain enter failure. Retry only liburing's pending SQ tail. */
        ring->failed = true;
        const struct timespec delay = { .tv_sec = 0, .tv_nsec = 100000000 };
        nanosleep(&delay, NULL);
    }
    for (;;) {
        struct io_uring_cqe *cqe;
        result = io_uring_wait_cqe(&ring->ring, &cqe);
        if (result == -EINTR) continue;
        if (result < 0) {
            ring->failed = true;
            const struct timespec delay = { .tv_sec = 0, .tv_nsec = 100000000 };
            nanosleep(&delay, NULL);
            continue;
        }
        if (io_uring_cqe_get_data64(cqe) != (uint64_t)user_data) {
            ring->failed = true;
            io_uring_cqe_seen(&ring->ring, cqe);
            continue;
        }
        result = cqe->res;
        io_uring_cqe_seen(&ring->ring, cqe);
        break;
    }
    if (ring->failed) {
        io_uring_queue_exit(&ring->ring);
        ring->exited = true;
    }
finish:
    free(path);
    return integer(env, result);
}

static void ring_free(struct node_ring *ring) {
    io_uring_free_probe(ring->probe);
    if (!ring->exited) io_uring_queue_exit(&ring->ring);
    free(ring);
}

static napi_value ring_close(napi_env env, napi_callback_info info) {
    napi_value args[1];
    size_t count = 1;
    if (napi_get_cb_info(env, info, &count, args, NULL, NULL) != napi_ok || count != 1)
        return integer(env, -EINVAL);
    struct node_uring *state = context(env);
    struct node_ring *ring = ring_of(env, args[0]);
    if (!state || !ring) return integer(env, -EBADF);
    struct node_ring **slot = &state->rings;
    while (*slot != ring) slot = &(*slot)->next;
    *slot = ring->next;
    ring_free(ring);
    return integer(env, 0);
}

static void finalize(napi_env env, void *data, void *hint) {
    (void)env; (void)hint;
    struct node_uring *state = data;
    while (state->rings) {
        struct node_ring *ring = state->rings;
        state->rings = ring->next;
        ring_free(ring);
    }
    free(state);
}

static napi_value initialize(napi_env env, napi_value exports) {
    struct node_uring *state = calloc(1, sizeof(*state));
    if (!state) { napi_throw_error(env, NULL, "Node uring allocation failed"); return NULL; }
    if (napi_set_instance_data(env, state, finalize, NULL) != napi_ok) { free(state); return NULL; }
    const napi_property_descriptor properties[] = {
        { "abiVersion", NULL, abi_version, NULL, NULL, NULL, napi_default, NULL },
        { "platform", NULL, platform, NULL, NULL, NULL, napi_default, NULL },
        { "architecture", NULL, architecture, NULL, NULL, NULL, napi_default, NULL },
        { "napiVersion", NULL, napi_version, NULL, NULL, NULL, napi_default, NULL },
        { "open", NULL, ring_open, NULL, NULL, NULL, napi_default, NULL },
        { "supports", NULL, supports, NULL, NULL, NULL, napi_default, NULL },
        { "execute", NULL, execute, NULL, NULL, NULL, napi_default, NULL },
        { "close", NULL, ring_close, NULL, NULL, NULL, napi_default, NULL },
    };
    if (napi_define_properties(env, exports, sizeof(properties) / sizeof(properties[0]), properties) != napi_ok)
        return NULL;
    return exports;
}

NAPI_MODULE(NODE_GYP_MODULE_NAME, initialize)
