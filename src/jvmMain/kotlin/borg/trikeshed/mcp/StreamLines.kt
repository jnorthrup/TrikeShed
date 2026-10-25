package borg.trikeshed.mcp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream

/**
 * [ChunkedLineSource] over a blocking [InputStream] (stdin, a socket handed to us as fd 0 by tcpd, a pipe). The framing is commonMain;
 * only the read is here. Blocking reads run on the IO dispatcher and are not interruptible: cancelling the caller does not unblock a
 * read that is waiting for input. Closing the stream (EOF) or ending the process does.
 */
class InputStreamLineSource(input: InputStream, bufferSize: Int = 8_192) :
    LineSource by ChunkedLineSource({ buffer -> withContext(Dispatchers.IO) { input.read(buffer, 0, buffer.size) } }, bufferSize)

/** [FramedLineSink] over an [OutputStream]: one `write` per reply, then a flush. */
class OutputStreamLineSink(output: OutputStream) :
    LineSink by FramedLineSink({ frame -> withContext(Dispatchers.IO) { output.write(frame); output.flush() } })
