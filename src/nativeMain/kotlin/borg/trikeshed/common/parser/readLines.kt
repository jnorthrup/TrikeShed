package borg.trikeshed.common.parser

import borg.trikeshed.common.parser.simple.CharSeries
import kotlinx.cinterop.*
import platform.posix.*

/** emulates the getline behavior instead of fgets
 *
  *
 *     FILE *fp;
 *     char *line = NULL;
 *     size_t len = 0;
 *     ssize_t read;
 *     fp = fopen("/etc/motd", "r");
 *     if (fp == NULL)
 *         exit(1);
 *     while ((read = getline(&line, &len, fp)) != -1) {
 *         printf("Retrieved line of length %zu :\n", read);
 *         printf("%s", line);
 *     }
 *     if (ferror(fp)) {
 *         /* handle error */
 *     }
 *     free(line);
 *     fclose(fp);
 *     return 0;
 * }
 */
actual fun readLines(path: String): Sequence<CharSeries> = memScoped {
    return sequence {
        val fp = fopen(path, "r")
        if (fp == null) {
            perror("fopen")
            exit(1)
        }

        val line: CPointerVarOf<CPointer<ByteVarOf<Byte>>> = alloc<CPointerVar<ByteVar>>()
        val len: ULongVarOf<size_t> = alloc<size_tVar>()
        len.value = 0u
        var read: ssize_t = 0.toLong()

        while (true) {
            read = getline(line.ptr, len.ptr, fp)
            if (read == -1L) break
            yield(CharSeries(line.value!!.toKString().trim()))
        }
        free(line.value)
        fclose(fp)
        if (ferror(fp) != 0) {
            perror("ferror")
            exit(1)
        }
    }
}

