/* SPDX-License-Identifier: GPL-2.0 WITH Linux-syscall-note */
/* TrikeShed cinterop: the K/N sysroot's kernel 4.9 linux/fs.h predates __kernel_rwf_t (4.13, with RWF_SUPPORTED),
 * which liburing's io_uring.h names; this is include/uapi/linux/fs.h's definition. */
#include_next <linux/fs.h>

#ifndef RWF_SUPPORTED
typedef int __kernel_rwf_t;
#endif
