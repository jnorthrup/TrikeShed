#include "trikeshed_kernels.h"
#include <math.h>

/* Branch-free selection: always store the ordinal, advance by the predicate. */
#define WHERE_BODY(CMP) for (int32_t i = 0; i < n; i++) { out[m] = i; m += (CMP); }
#define REFINE_BODY(CMP) for (int32_t j = 0; j < m; j++) { int32_t r = sel[j]; out[k] = r; k += (CMP); }

int32_t ts_where_f64(const double *restrict v, int32_t n, int32_t op, double x, int32_t *restrict out) {
    int32_t m = 0;
    switch (op) {
        case 0: WHERE_BODY(v[i] <  x) break;
        case 1: WHERE_BODY(v[i] <= x) break;
        case 2: WHERE_BODY(v[i] >  x) break;
        case 3: WHERE_BODY(v[i] >= x) break;
        case 4: WHERE_BODY(v[i] == x) break;
        default: WHERE_BODY(v[i] != x) break;
    }
    return m;
}

int32_t ts_refine_f64(const double *restrict v, const int32_t *sel, int32_t m, int32_t op, double x, int32_t *out) {
    int32_t k = 0;
    switch (op) {
        case 0: REFINE_BODY(v[r] <  x) break;
        case 1: REFINE_BODY(v[r] <= x) break;
        case 2: REFINE_BODY(v[r] >  x) break;
        case 3: REFINE_BODY(v[r] >= x) break;
        case 4: REFINE_BODY(v[r] == x) break;
        default: REFINE_BODY(v[r] != x) break;
    }
    return k;
}

int32_t ts_where_i64(const int64_t *restrict v, int32_t n, int32_t op, int64_t x, int32_t *restrict out) {
    int32_t m = 0;
    switch (op) {
        case 0: WHERE_BODY(v[i] <  x) break;
        case 1: WHERE_BODY(v[i] <= x) break;
        case 2: WHERE_BODY(v[i] >  x) break;
        case 3: WHERE_BODY(v[i] >= x) break;
        case 4: WHERE_BODY(v[i] == x) break;
        default: WHERE_BODY(v[i] != x) break;
    }
    return m;
}

int32_t ts_refine_i64(const int64_t *restrict v, const int32_t *sel, int32_t m, int32_t op, int64_t x, int32_t *out) {
    int32_t k = 0;
    switch (op) {
        case 0: REFINE_BODY(v[r] <  x) break;
        case 1: REFINE_BODY(v[r] <= x) break;
        case 2: REFINE_BODY(v[r] >  x) break;
        case 3: REFINE_BODY(v[r] >= x) break;
        case 4: REFINE_BODY(v[r] == x) break;
        default: REFINE_BODY(v[r] != x) break;
    }
    return k;
}

/* Sequential order is part of the contract (bit-identical to Kotlin); -ffp-contract=off keeps it. */
double ts_sum_f64_sel(const double *restrict v, const int32_t *restrict sel, int32_t m) {
    double s = 0.0;
    for (int32_t j = 0; j < m; j++) s += v[sel[j]];
    return s;
}

int64_t ts_sum_i64_sel(const int64_t *restrict v, const int32_t *restrict sel, int32_t m) {
    int64_t s = 0;
    for (int32_t j = 0; j < m; j++) s += v[sel[j]];
    return s;
}

void ts_mul_f64(const double *restrict a, const double *restrict b, const int32_t *restrict sel, int32_t m, double *restrict out) {
    for (int32_t j = 0; j < m; j++) { int32_t r = sel[j]; out[j] = a[r] * b[r]; }
}

void ts_affine_f64(const double *restrict a, double scale, double shift, const int32_t *restrict sel, int32_t m, double *restrict out) {
    for (int32_t j = 0; j < m; j++) out[j] = a[sel[j]] * scale + shift;
}

static inline int32_t slot_of(int64_t key, int bits) {
    return (int32_t)(((uint64_t)key * (uint64_t)-7046029254386353131LL) >> (64 - bits));
}

int32_t ts_group_ids_i64(const int64_t *restrict keys, const int32_t *restrict sel, int32_t m, int32_t *restrict ids,
                         int32_t *restrict first_row, int32_t *restrict table, int64_t *restrict table_keys, int32_t table_size) {
    int32_t mask = table_size - 1;
    int bits = 63 - __builtin_clzll((unsigned long long)table_size);
    int32_t groups = 0;
    for (int32_t j = 0; j < m; j++) {
        int64_t key = keys[sel[j]];
        int32_t slot = slot_of(key, bits);
        for (;;) {
            int32_t held = table[slot];
            if (held == 0) {
                table[slot] = groups + 1;
                table_keys[slot] = key;
                first_row[groups] = sel[j];
                ids[j] = groups++;
                break;
            }
            if (table_keys[slot] == key) { ids[j] = held - 1; break; }
            slot = (slot + 1) & mask;
        }
    }
    return groups;
}

void ts_sum_by_group_f64(const double *restrict v, const int32_t *restrict sel, const int32_t *restrict ids, int32_t m, double *restrict sums) {
    for (int32_t j = 0; j < m; j++) sums[ids[j]] += v[sel[j]];
}

void ts_sum_by_group_i64(const int64_t *restrict v, const int32_t *restrict sel, const int32_t *restrict ids, int32_t m, int64_t *restrict sums) {
    for (int32_t j = 0; j < m; j++) sums[ids[j]] += v[sel[j]];
}

void ts_count_by_group(const int32_t *restrict ids, int32_t m, int64_t *restrict counts) {
    for (int32_t j = 0; j < m; j++) counts[ids[j]]++;
}

void ts_cosine_f32(const float *restrict flat, int32_t n, int32_t dims, const float *restrict probe, float *restrict out) {
    float pn = 0.0f;
    for (int32_t d = 0; d < dims; d++) pn += probe[d] * probe[d];
    float probe_norm = sqrtf(pn);
    for (int32_t r = 0; r < n; r++) {
        float dot = 0.0f, rn = 0.0f;
        const float *row = flat + (int64_t)r * dims;
        for (int32_t d = 0; d < dims; d++) { float x = row[d]; dot += x * probe[d]; rn += x * x; }
        float norm = sqrtf(rn) * probe_norm;
        out[r] = norm == 0.0f ? 0.0f : dot / norm;
    }
}

void ts_dot_f32(const float *restrict flat, int32_t n, int32_t dims, const float *restrict probe, float *restrict out) {
    for (int32_t r = 0; r < n; r++) {
        float s = 0.0f;
        const float *row = flat + (int64_t)r * dims;
        for (int32_t d = 0; d < dims; d++) s += row[d] * probe[d];
        out[r] = s;
    }
}

void ts_l2sq_f32(const float *restrict flat, int32_t n, int32_t dims, const float *restrict probe, float *restrict out) {
    for (int32_t r = 0; r < n; r++) {
        float s = 0.0f;
        const float *row = flat + (int64_t)r * dims;
        for (int32_t d = 0; d < dims; d++) { float x = row[d] - probe[d]; s += x * x; }
        out[r] = s;
    }
}
