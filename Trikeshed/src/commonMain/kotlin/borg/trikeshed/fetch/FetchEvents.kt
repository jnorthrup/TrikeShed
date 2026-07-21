package borg.trikeshed.fetch

/**
 * Enum to specify which fetching tool to use.
 */
enum class FetchTool {
    CURL,
    ARIA2C
}

/**
 * Event to request a fetch operation.
 *
 * @property tool The [FetchTool] to use (CURL or ARIA2C).
 * @property url The primary URL to fetch. For ARIA2C, this might be one of many.
 * @property args A list of arguments to pass to the underlying command-line tool symlink.
 *              This allows for more flexibility than just a params map.
 *              For example, for curl: listOf("-H", "X-Custom: value", url)
 *              For aria2c: listOf("--max-concurrent-downloads=5", url1, url2)
 * @property correlationId An optional ID to correlate this request with its response.
 */
data class FetchRequest(
    val tool: FetchTool,
    val url: String, // Retained for simplicity, but primary arguments should be in `args`
    val args: List<String>,
    val correlationId: String? = null
)

/**
 * Event representing the response of a fetch operation.
 *
 * @property request The original [FetchRequest] that triggered this response.
 * @property success Boolean indicating if the operation was considered successful (e.g., exit code 0).
 * @property exitCode The exit code of the executed process.
 * @property stdout The standard output from the command.
 * @property stderr The standard error output from the command.
 * @property correlationId An optional ID to correlate this response with its request.
 */
data class FetchResponse(
    val request: FetchRequest,
    val success: Boolean,
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val correlationId: String? = null
)
