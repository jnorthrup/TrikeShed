#ifndef TRIKESHED_KERNELS_H
#define TRIKESHED_KERNELS_H
#include <stdint.h>

/* Column kernels behind TrikeShed's ColumnOperations seam. Plain C ABI; every function returns
 * exactly what the Kotlin kernels in lib/<primitive>p return. op is Comparison.ordinal:
 * 0 LT, 1 LE, 2 GT, 3 GE, 4 EQ, 5 NE. Floating-point reductions keep Kotlin's left-to-right
 * order (no reassociation), so sums are bit-identical; the selection, arithmetic and gather
 * loops carry no such dependency and are the ones the autovectorizer takes. */

int32_t ts_where_f64(const double *restrict v, int32_t n, int32_t op, double x, int32_t *restrict out);
/* refine compacts in place: out may be sel, so neither is restrict. */
int32_t ts_refine_f64(const double *restrict v, const int32_t *sel, int32_t m, int32_t op, double x, int32_t *out);
int32_t ts_where_i64(const int64_t *restrict v, int32_t n, int32_t op, int64_t x, int32_t *restrict out);
int32_t ts_refine_i64(const int64_t *restrict v, const int32_t *sel, int32_t m, int32_t op, int64_t x, int32_t *out);
double  ts_sum_f64_sel(const double *restrict v, const int32_t *restrict sel, int32_t m);
int64_t ts_sum_i64_sel(const int64_t *restrict v, const int32_t *restrict sel, int32_t m);
void    ts_mul_f64(const double *restrict a, const double *restrict b, const int32_t *restrict sel, int32_t m, double *restrict out);
void    ts_affine_f64(const double *restrict a, double scale, double shift, const int32_t *restrict sel, int32_t m, double *restrict out);
int32_t ts_group_ids_i64(const int64_t *restrict keys, const int32_t *restrict sel, int32_t m, int32_t *restrict ids,
                         int32_t *restrict first_row, int32_t *restrict table, int64_t *restrict table_keys, int32_t table_size);
void    ts_sum_by_group_f64(const double *restrict v, const int32_t *restrict sel, const int32_t *restrict ids, int32_t m, double *restrict sums);
void    ts_sum_by_group_i64(const int64_t *restrict v, const int32_t *restrict sel, const int32_t *restrict ids, int32_t m, int64_t *restrict sums);
void    ts_count_by_group(const int32_t *restrict ids, int32_t m, int64_t *restrict counts);
void    ts_cosine_f32(const float *restrict flat, int32_t n, int32_t dims, const float *restrict probe, float *restrict out);
void    ts_dot_f32(const float *restrict flat, int32_t n, int32_t dims, const float *restrict probe, float *restrict out);
void    ts_l2sq_f32(const float *restrict flat, int32_t n, int32_t dims, const float *restrict probe, float *restrict out);

#endif
