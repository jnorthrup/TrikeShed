# Fetching with Symlink Reactor

This document describes the mechanism for performing client fetches (e.g., HTTP GET) using a symlink-based dispatch system integrated with the Trikeshed reactor pattern.

## Overview

The system allows the main Trikeshed application to invoke `curl`-like or `aria2c`-like functionality by executing a symlink that points back to itself. The application detects it's being run via a specific symlink name and alters its behavior to mimic the corresponding command-line tool, using standard input/output.

A dedicated `FetchingReactor` can then be used within the main application to manage these fetch operations as asynchronous tasks.

## Symlink Setup

To use this functionality, create symlinks in your system's PATH or a known directory, pointing to the main Trikeshed application executable (e.g., `trikeshed.jar` or a native binary).

Recommended symlink names:
-   `trike-curl`
-   `trike-aria2c`

**Example (Linux/macOS):**
```bash
# Assuming your Trikeshed application is trikeshed.jar and it's executable
# (e.g., via a shell script wrapper or `java -jar trikeshed.jar`)
# Or if it's a native executable at /opt/trikeshed/bin/trikeshed

# Example 1: Symlink to a wrapper script for a JAR
# (Create a wrapper script `run-trikeshed.sh` first)
# #!/bin/bash
# java -jar /path/to/your/trikeshed.jar "$@"
# chmod +x run-trikeshed.sh
# sudo ln -s /path/to/run-trikeshed.sh /usr/local/bin/trike-curl
# sudo ln -s /path/to/run-trikeshed.sh /usr/local/bin/trike-aria2c

# Example 2: Symlink to a native executable
# sudo ln -s /opt/trikeshed/bin/trikeshed /usr/local/bin/trike-curl
# sudo ln -s /opt/trikeshed/bin/trikeshed /usr/local/bin/trike-aria2c
```

When the application starts, it checks the name it was called with (`argv[0]`). If it matches `trike-curl` or `trike-aria2c`, it enters a special mode.

## `trike-curl` Mode

When invoked as `trike-curl`, the application mimics basic `curl` functionality.

**Supported Options (Current):**
-   `URL`: The URL to fetch (HTTP GET).
-   `-i`, `--include`: Include HTTP response headers in the output.
-   `-H <header>`, `--header <header>`: Pass a custom header to the request (e.g., `-H "Authorization: Bearer token"`).

**Example Usage (Command Line):**
```bash
trike-curl http://example.com
trike-curl -i http://example.com
trike-curl -H "X-Custom-Header: MyValue" http://example.com
```
Output is sent to `stdout`, errors to `stderr`.

## `trike-aria2c` Mode

When invoked as `trike-aria2c`, the application mimics basic `aria2c` functionality.

**Supported Options (Current - Stub):**
-   This mode is currently a basic stub. It will acknowledge arguments and print simulated actions.
-   It does not perform actual multi-connection downloads yet.

**Example Usage (Command Line):**
```bash
trike-aria2c http://example.com/file1.zip http://example.com/file2.zip
```

## Reactor Integration (`FetchingReactor`)

The `FetchingReactor` is responsible for initiating and managing these fetch operations asynchronously within a running Trikeshed application.

**Events:**
-   `FetchRequest(tool: FetchTool, url: String, args: List<String>, correlationId: String? = null)`:
    *   `tool`: `FetchTool.CURL` or `FetchTool.ARIA2C`.
    *   `url`: The primary URL (mainly for informational purposes, the full command is in `args`).
    *   `args`: The complete list of arguments to pass to the symlinked command (e.g., `listOf("-i", "http://example.com")` for `trike-curl`).
    *   `correlationId`: Optional ID to track the request.
-   `FetchResponse(request: FetchRequest, success: Boolean, exitCode: Int, stdout: String, stderr: String, correlationId: String? = null)`:
    *   Contains the result of the fetch operation.

**Usage:**
1.  Create an instance of `FetchingReactor`.
2.  Start the reactor (e.g., `myFetchingReactor.start()`).
3.  Register handlers for `FetchResponse` events (using `EventType.RESPONSE`) if you need to process results.
    ```kotlin
    myFetchingReactor.on(FetchEventTypes.FETCH_RESPONSE) { event ->
        val response = event.data as? FetchResponse
        if (response != null) {
            // Process the response
            println("Fetched ${response.request.url}, success: ${response.success}")
            println("Output: ${response.stdout}")
        }
    }
    ```
4.  Emit `FetchRequest` events to the `FetchingReactor`:
    ```kotlin
    val request = FetchRequest(
        tool = FetchTool.CURL,
        url = "http://example.com",
        args = listOf("-i", "http://example.com"),
        correlationId = "myFetchOp123"
    )
    // Assuming 'AppEventBus' is your way to send events to specific reactors,
    // or directly if you have a reference to the reactor instance.
    // The FetchingReactor itself listens for EventType.REQUEST with FetchRequest data.
    myFetchingReactor.emit(Event(FetchEventTypes.FETCH_REQUEST, request, Clock.System.now().toEpochMilliseconds()))
    ```

The `FetchingReactor` will then use the platform's `executeProcess` capability to run the appropriate symlink (`trike-curl` or `trike-aria2c`) with the provided arguments. The `stdout`, `stderr`, and `exitCode` from that process are captured and emitted in the `FetchResponse`.

## Platform Dependencies

The fetching mechanism relies on `expect`/`actual` implementations for:
-   `getProgramName()`: To determine the invocation mode.
-   `getProgramArguments()`: To get arguments passed to the symlink.
-   `executeProcess(...)`: To launch the symlinked process.
-   `httpGet(...)`: Used by `trike-curl` mode internally for HTTP requests.
-   `writeToStdOut(...)`, `writeToStdErr(...)`, `exitProgram(...)`: For CLI mode behavior.

Currently, JVM implementations are provided. Native implementations are stubbed and would need to be completed for full cross-platform support.
The `trike-curl` mode currently only supports HTTP GET requests.
