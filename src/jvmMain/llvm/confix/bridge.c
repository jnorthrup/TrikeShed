/* Bounded ownership ABI for the pinned mlir-nix Confix scanner. */
/* Compiler builtin integer types keep this ABI independent of host SDK headers. */
typedef __INT32_TYPE__ int32_t;
typedef __UINT32_TYPE__ uint32_t;
typedef __UINT64_TYPE__ uint64_t;
typedef __UINT8_TYPE__ uint8_t;
typedef __SIZE_TYPE__ size_t;
extern void *calloc(size_t, size_t);
extern void free(void *);
#define NULL ((void *)0)
#define offsetof(type, field) __builtin_offsetof(type, field)

typedef struct { int32_t size; uint8_t *data; int32_t pos, limit, mark; } Chars;
typedef struct {
    int32_t total, fault, at, room;
    int32_t *spans; uint8_t *tags; int32_t *depths, *starts, *kids, *work;
} Flat;
typedef struct { Chars source; Flat flat; uint64_t bytes; } Scan;
_Static_assert(sizeof(void *) == 8, "This retained profile requires 64-bit pointers");
_Static_assert(sizeof(Chars) == 32 && offsetof(Chars, data) == 8, "CharSeries ABI");
_Static_assert(sizeof(Flat) == 64 && offsetof(Flat, spans) == 16 && offsetof(Flat, work) == 56, "Flat ABI");
extern int32_t confix_scan_json(Chars *, Flat *);
extern int32_t confix_scan_cbor(Chars *, Flat *);
extern int32_t confix_scan_yaml(Chars *, Flat *);
static uint64_t live_bytes;
static uint64_t aligned(uint64_t n) { return (n + 7u) & ~7ull; }

int32_t confix_abi(void) { return 1; }
uint64_t confix_live_bytes(void) { return live_bytes; }

/* One allocation contains the handle, input and every scanner array. */
Scan *confix_open(int32_t size, int32_t room) {
    if (size < 0 || size > 65536 || room < 1 || room > 16384) return NULL;
    uint64_t n = (uint32_t)size, r = (uint32_t)room;
    uint64_t work = 9 * (n + 1) > 3 * r ? 9 * (n + 1) : 3 * r;
    uint64_t bytes = aligned(sizeof(Scan)) + aligned(n) + aligned(8*r) + aligned(r)
        + aligned(4*r) + aligned(4*(r+1)) + aligned(4*r) + aligned(4*work);
    Scan *s = calloc(1, (size_t)bytes);
    if (!s) return NULL;
    uint8_t *p = (uint8_t *)s + aligned(sizeof(Scan));
    s->source.size = size; s->source.data = p; s->source.limit = size; p += aligned(n);
    s->flat.room = room;
    s->flat.spans = (int32_t *)p; p += aligned(8*r);
    s->flat.tags = p; p += aligned(r);
    s->flat.depths = (int32_t *)p; p += aligned(4*r);
    s->flat.starts = (int32_t *)p; p += aligned(4*(r+1));
    s->flat.kids = (int32_t *)p; p += aligned(4*r);
    s->flat.work = (int32_t *)p;
    s->bytes = bytes; live_bytes += bytes;
    return s;
}

/* Packed little-endian transport lanes; this is independent of host endian. */
int32_t confix_write(Scan *s, int32_t offset, int32_t packed) {
    if (!s || offset < 0 || offset >= s->source.size) return -1;
    for (int32_t k=0; k<4 && offset+k<s->source.size; k++)
        s->source.data[offset+k] = (uint8_t)((uint32_t)packed >> (8*k));
    return 0;
}
int32_t confix_run(Scan *s, int32_t format) {
    if (!s) return -1;
    switch (format) {
        case 0: return confix_scan_json(&s->source, &s->flat);
        case 1: return confix_scan_cbor(&s->source, &s->flat);
        case 2: return confix_scan_yaml(&s->source, &s->flat);
        default: return -1;
    }
}
int32_t confix_header(Scan *s, int32_t field) {
    if (!s) return -1;
    switch (field) { case 0: return s->flat.total; case 1: return s->flat.fault; case 2: return s->flat.at; default: return -1; }
}
/* Tree fields are unavailable on a scanner fault. No partial rows escape. */
int32_t confix_cell(Scan *s, int32_t row, int32_t field) {
    if (!s || s->flat.fault || row<0 || row>=s->flat.total || row>=s->flat.room) return -1;
    switch (field) {
        case 0: return s->flat.spans[2*row]; case 1: return s->flat.spans[2*row+1];
        case 2: return s->flat.tags[row]; case 3: return s->flat.depths[row];
        case 4: return s->flat.starts[row+1]-s->flat.starts[row]; default: return -1;
    }
}
int32_t confix_child(Scan *s, int32_t row, int32_t child) {
    if (!s || s->flat.fault || row<0 || row>=s->flat.total || row>=s->flat.room || child<0) return -1;
    int32_t start=s->flat.starts[row], end=s->flat.starts[row+1];
    if (start<0 || end<start || end>s->flat.total || child>=end-start) return -1;
    return s->flat.kids[start+child];
}
void confix_close(Scan *s) { if (s) { live_bytes -= s->bytes; free(s); } }
