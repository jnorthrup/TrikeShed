package simple

import kotlinx.cinterop.convert
import platform.posix.*
import platform.posix.mode_t as __mode_t

/**
 * All of these system calls return a stat structure, which contains the following fields:

struct stat {
dev_t     st_dev;     /* ID of device containing file */
ino_t     st_ino;     /* inode number */
mode_t    st_mode;    /* protection */
nlink_t   st_nlink;   /* number of hard links */
uid_t     st_uid;     /* user ID of owner */
gid_t     st_gid;     /* group ID of owner */
dev_t     st_rdev;    /* device ID (if special file) */
off_t     st_size;    /* total size, in bytes */
blksize_t st_blksize; /* blocksize for file system I/O */
blkcnt_t  st_blocks;  /* number of 512B blocks allocated */
time_t    st_atime;   /* time of last access */
time_t    st_mtime;   /* time of last modification */
time_t    st_ctime;   /* time of last status change */
};
The st_dev field describes the device on which this file resides. (The major(3) and minor(3) macros may be useful to decompose the device ID in this field.)
The st_rdev field describes the device that this file (inode) represents.

The st_size field gives the size of the file (if it is a regular file or a symbolic link) in bytes. The size of a symbolic link is the length of the pathname it contains, without a terminating null byte.

The st_blocks field indicates the number of blocks allocated to the file, 512-byte units. (This may be smaller than st_size/512 when the file has holes.)

The st_blksize field gives the "preferred" blocksize for efficient file system I/O. (Writing to a file in smaller chunks may cause an inefficient read-modify-rewrite.)

Not all of the Linux file systems implement all of the time fields. Some file system types allow mounting in such a way that file and/or directory accesses do not cause an update of the st_atime field. (See noatime, nodiratime, and relatime in mount(8), and related information in mount(2).) In addition, st_atime is not updated if a file is opened with the O_NOATIME; see open(2).

The field st_atime is changed by file accesses, for example, by execve(2), mknod(2), pipe(2), utime(2) and read(2) (of more than zero bytes). Other routines, like mmap(2), may or may not update st_atime.

The field st_mtime is changed by file modifications, for example, by mknod(2), truncate(2), utime(2) and write(2) (of more than zero bytes). Moreover, st_mtime of a directory is changed by the creation or deletion of files in that directory. The st_mtime field is not changed for changes in owner, group, hard link count, or mode.

The field st_ctime is changed by writing or by setting inode information (i.e., owner, group, link count, mode, etc.).

The following POSIX macros are defined to check the file type using the st_mode field:

 */
enum class PosixStatMode(state_: Int, val state: UInt = state_.convert()) {

    /** is it a regular file?*/
    S_ISREG(S_IFREG),

    /**directory?*/
    S_ISDIR(S_IFDIR),

    /**character device?*/
    S_ISCHR(S_IFCHR),

    /**block device?*/
    S_ISBLK(S_IFBLK),

    /**FIFO (named pipe)?*/
    S_ISFIFO(S_IFIFO),

    /**symbolic link? *//*Not in POSIX.1-1996.*//*)*/
    S_ISLNK(S_IFLNK),

    /**socket? *//*Not in POSIX.1-1996.*//*)*/
    S_ISSOCK(S_IFSOCK)
    ;

    operator fun invoke(mode:
                        __mode_t ): Boolean = __S_ISTYPE((mode), this.state.convert())

    companion object {
        fun __S_ISTYPE(mode: __mode_t, mask: __mode_t): Boolean = mode and S_IFMT.convert() == mask
    }

}