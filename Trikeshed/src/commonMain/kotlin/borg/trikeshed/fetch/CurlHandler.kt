package borg.trikeshed.fetch

import borg.trikeshed.platform.httpGet
import borg.trikeshed.platform.writeToStdOut
import borg.trikeshed.platform.writeToStdErr
import borg.trikeshed.platform.exitProgram // Assuming this will be added to System.kt expect/actual

/**
 * Handles the program invocation when it's called as 'trike-curl'.
 * Mimics basic curl functionality.
 *
 * @param args Command line arguments passed to 'trike-curl'.
 * @return An exit code (0 for success, non-zero for failure).
 */
suspend fun handleCurlInvocation(args: List<String>): Int {
    if (args.isEmpty()) {
        writeToStdErr("trike-curl: try 'trike-curl --help' for more information\n")
        return 1
    }

    // Very basic argument parsing
    var urlString: String? = null
    var includeHeaders = false
    val requestHeaders = mutableMapOf<String, String>()
    var nextIsHeader = false
    var customMethod: String? = null // Not used yet, but for future -X

    // Simplified parsing loop
    val argIterator = args.iterator()
    while (argIterator.hasNext()) {
        val arg = argIterator.next()
        when {
            arg == "-i" || arg == "--include" -> includeHeaders = true
            arg == "-H" || arg == "--header" -> {
                if (argIterator.hasNext()) {
                    val headerLine = argIterator.next()
                    val parts = headerLine.split(":", limit = 2)
                    if (parts.size == 2) {
                        requestHeaders[parts[0].trim()] = parts[1].trim()
                    } else {
                        writeToStdErr("trike-curl: invalid header format '$headerLine'\n")
                        return 1
                    }
                } else {
                    writeToStdErr("trike-curl: option $arg: requires parameter\n")
                    return 1
                }
            }
            arg == "-X" || arg == "--request" -> {
                 if (argIterator.hasNext()) {
                    customMethod = argIterator.next().uppercase()
                 } else {
                    writeToStdErr("trike-curl: option $arg: requires parameter\n")
                    return 1
                 }
            }
            arg.startsWith("-") -> {
                // Ignore unknown options for now, or print warning
                writeToStdErr("trike-curl: warning: ignoring unknown option: $arg\n")
            }
            else -> {
                if (urlString == null) {
                    urlString = arg
                } else {
                    writeToStdErr("trike-curl: warning: ignoring extra argument: $arg\n")
                }
            }
        }
    }

    if (urlString == null) {
        writeToStdErr("trike-curl: no URL specified!\n")
        return 1
    }
    
    // For now, only GET is implemented via httpGet
    if (customMethod != null && customMethod != "GET") {
        writeToStdErr("trike-curl: warning: method '$customMethod' not yet supported, using GET.\n")
    }

    val response = httpGet(urlString!!, requestHeaders)

    if (response.statusCode == 0 && response.errorBody?.startsWith("Exception:") == true) {
        writeToStdErr("trike-curl: (error) ${response.errorBody}\n")
        return 1
    }
    
    if (response.statusCode == 0 && response.errorBody?.startsWith("Not implemented") == true) {
         writeToStdErr("trike-curl: (error) Network request functionality is not implemented for the current platform.\n")
         return 1
    }


    if (includeHeaders) {
        writeToStdOut("HTTP/1.1 ${response.statusCode} ${HttpStatusCodes[response.statusCode] ?: ""}\n")
        response.headers.forEach { key, values ->
            values.forEach { value ->
                // Don't print null keys (often status line from HttpURLConnection's headerFields)
                key?.let { writeToStdOut("$key: $value\n") }
            }
        }
        writeToStdOut("\n") // Blank line between headers and body
    }

    writeToStdOut(response.body)
    if (response.errorBody != null && response.statusCode !in 200..299) {
        // Typically curl sends error page content to stdout, not stderr, unless -f or --fail is used
        writeToStdOut(response.errorBody) 
    }
    
    // Curl exit codes are numerous. Basic success/failure for now.
    // 0 on success. Non-zero on error.
    // Example: 6 for "Couldn't resolve host".
    return if (response.statusCode in 200..299) 0 else 1
}

// Basic HTTP status codes map
private val HttpStatusCodes = mapOf(
    200 to "OK",
    201 to "Created",
    204 to "No Content",
    301 to "Moved Permanently",
    302 to "Found",
    400 to "Bad Request",
    401 to "Unauthorized",
    403 to "Forbidden",
    404 to "Not Found",
    500 to "Internal Server Error"
    // Add more as needed
)

// Need to add expect/actual for these stdio functions and exitProgram
// For now, defining them here to satisfy the CurlHandler code.
// These will be moved to System.kt and implemented properly.
internal expect fun writeToStdOut(message: String)
internal expect fun writeToStdErr(message: String)
internal expect fun exitProgram(exitCode: Int)
