package borg.trikeshed.tilting.zran

import borg.trikeshed.tilting.zran.VERBOSITY_LEVEL.VERBOSITY_NORMAL

//#define SPAN 10485760L      /* desired distance between access points */
//#define WINSIZE 32768U      /* sliding window size */
//#define CHUNK 16384         /* file input buffer size */
//#define UNCOMPRESSED_WINDOW UINT32_MAX // window is an uncompressed WINSIZE size window
//#define GZIP_INDEX_IDENTIFIER_STRING    "gzipindx"  // default index version (v0)
//#define GZIP_INDEX_IDENTIFIER_STRING_V1 "gzipindX"  // index version with line number info
//#define GZIP_INDEX_HEADER_SIZE   16  // header size in bytes of gztool's .gzi files
//#define GZIP_HEADER_SIZE_BY_ZLIB 10  // header size in bytes of gzip files created by zlib:
////github.com/madler/zlib/blob/master/zlib.h
////     If deflateSetHeader is not used, the default gzip header has text false,
////   the time set to zero, and os set to 255, with no extra, name, or comment
////   fields.
//// default waiting time in seconds when supervising a growing gzip file:
//#define WAITING_TIME 4
//// how many CHUNKs will be decompressed in advance if it is needed (parameter gzip_stream_may_be_damaged, `-p`)
//#define CHUNKS_TO_DECOMPRESS_IN_ADVANCE 3
//// how many CHUNKs will be look to backwards for a new good gzip reentry point after an error is found (with `-p`)
//#define CHUNKS_TO_LOOK_BACKWARDS 3

//convert the above to kotlin constants
const val SPAN = 10485760L      /* desired distance between access points */
const val WINSIZE = 32768U      /* sliding window size */
const val CHUNK = 16384         /* file input buffer size */
const val UNCOMPRESSED_WINDOW = UInt.MAX_VALUE // window is an uncompressed WINSIZE size window
const val GZIP_INDEX_IDENTIFIER_STRING = "gzipindx"  // default index version (v0)
const val GZIP_INDEX_IDENTIFIER_STRING_V1 = "gzipindX"  // index version with line number info
const val GZIP_INDEX_HEADER_SIZE = 16  // header size in bytes of gztool's .gzi files
const val GZIP_HEADER_SIZE_BY_ZLIB = 10  // header size in bytes of gzip files created by zlib:
//github.com/madler/zlib/blob/master/zlib.h



/* access point entry */
//struct point {
//    uint64_t out;          /* corresponding offset in uncompressed data */
//    uint64_t in;           /* offset in input file of first full byte */
//    uint32_t bits;         /* number of bits (1-7) from byte at in - 1, or 0 */
//    uint64_t window_beginning;/* offset at index file where this compressed window is stored */
//    uint32_t window_size;  /* size of (compressed) window */
//    unsigned char *window; /* preceding 32K of uncompressed data, compressed */
//    // index v1:
//    uint64_t line_number;  /* if index_version == 1 stores line number at this index point */
//};
// NOTE: window_beginning is not stored on disk, it's an on-memory-only value
//convert the above to kotlin
data class Point @OptIn(ExperimentalUnsignedTypes::class) constructor(
    var out: ULong,          /* corresponding offset in uncompressed data */
    var `in`: ULong,           /* offset in input file of first full byte */
    var bits: UInt,         /* number of bits (1-7) from byte at in - 1, or 0 */
    var window_beginning: ULong,/* offset at index file where this compressed window is stored */
    var window_size: UInt,  /* size of (compressed) window */
    var window: UByteArray, /* preceding 32K of uncompressed data, compressed */
    var line_number: ULong  /* if index_version == 1 stores line number at this index point */
)




/* access point list */
//struct access {
//    uint64_t have;      /* number of list entries filled in */
//    uint64_t size;      /* number of list entries allocated */
//    uint64_t file_size; /* size of uncompressed file (useful for bgzip files) */
//    struct point *list; /* allocated list */
//    char *file_name;    /* path to index file */
//    int index_complete; /* 1: index is complete; 0: index is (still) incomplete */
//    // index v1:
//    int index_version;  /* 0: default; 1: index with line numbers */
//    uint32_t line_number_format; /* 0: linux \r | windows \n\r; 1: mac \n */
//    uint64_t number_of_lines; /* number of lines (only used with v1 index format) */
//};
// NOTE: file_name, index_complete and index_version are not stored on disk (on-memory-only values)

//convert the above to kotlin

data class Access(
    var have: Long,      /* number of list entries filled in */
    var size: Long,      /* number of list entries allocated */
    var file_size: Long, /* size of uncompressed file (useful for bgzip files) */
    var list: Array<Point>, /* allocated list */
    var file_name: String,    /* path to index file */
    var index_complete: Int, /* 1: index is complete; 0: index is (still) incomplete */
    var index_version: Int,  /* 0: default; 1: index with line numbers */
    var line_number_format: Int, /* 0: linux \r | windows \n\r; 1: mac \n */
    var number_of_lines: Long /* number of lines (only used with v1 index format) */
)

/* generic struct to return a function error code and a value */
//struct returned_output {
//    uint64_t value;
//    int error;
//};

//convert the above to kotlin
data class ReturnedOutput(
    var value: Long,
    var error: Int
)

//enum EXIT_RETURNED_VALUES {
//    // used for app exit values:
//    EXIT_OK = 0,
//    EXIT_GENERIC_ERROR = 1,
//    EXIT_INVALID_OPTION = 2,
//
//    // used with returned_output.error, not in app exit values:
//    // +100 not to crush with Z_* values (zlib): //www.zlib.net/manual.html
//    // (and also not to crush with GZIP_MARK_FOUND_TYPE values... though
//    // they are used only for decompress_in_advance()'s ret.error value)
//    EXIT_FILE_OVERWRITTEN = 100,
//};

//convert the above to kotlin
enum class ExitReturnedValues(val value1: Int? = null) {
    // used for app exit values:
    EXIT_OK ,
    EXIT_GENERIC_ERROR ,
    EXIT_INVALID_OPTION ,

    // used with returned_output.error, not in app exit values:
    // +100 not to crush with Z_* values (zlib): //www.zlib.net/manual.html
    // (and also not to crush with GZIP_MARK_FOUND_TYPE values... though
    // they are used only for decompress_in_advance()'s ret.error value)
    EXIT_FILE_OVERWRITTEN(100);
    val value: Int
        get() = value1 ?: ordinal
}

//enum class INDEX_AND_EXTRACTION_OPTIONS {
//    JUST_CREATE_INDEX = 1, SUPERVISE_DO,
//    SUPERVISE_DO_AND_EXTRACT_FROM_TAIL, EXTRACT_FROM_BYTE,
//    EXTRACT_TAIL, COMPRESS_AND_CREATE_INDEX, DECOMPRESS,
//    EXTRACT_FROM_LINE };

//convert the above to kotlin
enum class IndexAndExtractionOptions  {
    JUST_CREATE_INDEX ,
    SUPERVISE_DO ,
    SUPERVISE_DO_AND_EXTRACT_FROM_TAIL ,
    EXTRACT_FROM_BYTE ,
    EXTRACT_TAIL ,
    COMPRESS_AND_CREATE_INDEX ,
    DECOMPRESS ,
    EXTRACT_FROM_LINE ;
    val value: Int
        get() =  ordinal.inc()
}
enum class ACTION{ ACT_NOT_SET, ACT_EXTRACT_FROM_BYTE, ACT_COMPRESS_CHUNK, ACT_DECOMPRESS_CHUNK,
    ACT_CREATE_INDEX, ACT_LIST_INFO, ACT_HELP, ACT_SUPERVISE, ACT_EXTRACT_TAIL,
    ACT_EXTRACT_TAIL_AND_CONTINUE, ACT_COMPRESS_AND_CREATE_INDEX, ACT_DECOMPRESS,
    ACT_EXTRACT_FROM_LINE };

//enum VERBOSITY_LEVEL { VERBOSITY_NONE = 0, VERBOSITY_NORMAL = 1, VERBOSITY_EXCESSIVE = 2,
//    VERBOSITY_MANIAC = 3, VERBOSITY_CRAZY = 4, VERBOSITY_NUTS = 5 };

//convert the above to kotlin
 enum class VERBOSITY_LEVEL(val value1: Int? = null) {
    VERBOSITY_NONE ,
    VERBOSITY_NORMAL ,
    VERBOSITY_EXCESSIVE ,
    VERBOSITY_MANIAC ,
    VERBOSITY_CRAZY ,
    VERBOSITY_NUTS ;
    val value: Int
        get() = value1 ?: ordinal}

//enum VERBOSITY_LEVEL verbosity_level = VERBOSITY_NORMAL;

//convert the above to kotlin
var verbosity_level = VERBOSITY_NORMAL


// values returned by decompress_in_advance() in ret.error value:
//enum GZIP_MARK_FOUND_TYPE {
//    GZIP_MARK_ERROR // GZIP_MARK_ERROR informs that recovery is not possible, but
//    // also that process will try to continue without recovery.
//    = 8, // All values greater than Z_* zlib's return codes, because
//    // a ret.error is used at decompress_in_advance() for both
//    // zlib's outputs and the function output (GZIP_MARK_FOUND_TYPE).
//    GZIP_MARK_FATAL_ERROR, // This value aborts process, 'cause a compulsory fseeko() failed.
//    GZIP_MARK_NONE,        // Decompress_in_advance() didn't find an error in gzip stream (all ok!).
//    GZIP_MARK_BEGINNING,   // An error was found, and a new complete gzip stream was found
//    // to reinitiate process at ret.value byte.
//    GZIP_MARK_FULL_FLUSH   // An error was found, and a new gzip-Z_FULL_FLUSH was found
//    // to reinitiate process at ret.value byte.
//};

//convert the above to kotlin
enum class GZIP_MARK_FOUND_TYPE {
    GZIP_MARK_ERROR, // GZIP_MARK_ERROR informs that recovery is not possible, but
    // also that process will try to continue without recovery.
    GZIP_MARK_FATAL_ERROR, // This value aborts process, 'cause a compulsory fseeko() failed.
    GZIP_MARK_NONE,        // Decompress_in_advance() didn't find an error in gzip stream (all ok!).
    GZIP_MARK_BEGINNING,   // An error was found, and a new complete gzip stream was found
    // to reinitiate process at ret.value byte.
    GZIP_MARK_FULL_FLUSH   // An error was found, and a new gzip-Z_FULL_FLUSH was found
    // to reinitiate process at ret.value byte.
    ;
    val value: Int
        get() =  ordinal+8
}

// values used to initialize or continue processing with decompress_in_advance() function:
//enum DECOMPRESS_IN_ADVANCE_INITIALIZERS {
//    DECOMPRESS_IN_ADVANCE_RESET,     // initial total reset
//    DECOMPRESS_IN_ADVANCE_CONTINUE,  // no reset, just continue processing
//    DECOMPRESS_IN_ADVANCE_SOFT_RESET // reset all but last_correct_reentry_point_returned
//    // in order to continue processing the same gzip stream.
//};

//convert the above to kotlin
enum class DECOMPRESS_IN_ADVANCE_INITIALIZERS {
    DECOMPRESS_IN_ADVANCE_RESET,     // initial total reset
    DECOMPRESS_IN_ADVANCE_CONTINUE,  // no reset, just continue processing
    DECOMPRESS_IN_ADVANCE_SOFT_RESET // reset all but last_correct_reentry_point_returned
    // in order to continue processing the same gzip stream.
    ;
    val value: Int
        get() =  ordinal
}
