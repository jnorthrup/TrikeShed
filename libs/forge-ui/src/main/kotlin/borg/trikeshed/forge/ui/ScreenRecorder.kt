package borg.trikeshed.forge.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.awt.Rectangle
import java.awt.Robot
import java.awt.Toolkit
import java.awt.image.BufferedImage
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.imageio.ImageIO

/**
 * ScreenRecorder — captures the screen at a target FPS, writes raw PNG frames
 * to a temp directory, and assembles them into an MP4 via ffmpeg.
 *
 * Two recording modes:
 *   1. [recordWindow] — captures a specific window's AWT bounds (preferred).
 *   2. [recordRegion] — captures an explicit pixel rectangle (fallback).
 *
 * Subtitle/narration frames are burnt in via the ffmpeg drawtext filter.
 *
 * Usage:
 *   val rec = ScreenRecorder(fps = 30)
 *   rec.startRecording(window)
 *   // ... do things ...
 *   rec.setNarration("Step 2: drag card")
 *   // ...
 *   val mp4 = rec.stopAndExport(File("output.mp4"))
 */
class ScreenRecorder(
    val fps: Int = 30,
    val ffmpegPath: String = "ffmpeg",
) {
    // ── State ────────────────────────────────────────────────────────────────

    enum class State { IDLE, RECORDING, EXPORTING }

    @Volatile var state: State = State.IDLE
        private set

    val frameCount: Int get() = _frameCount.get()
    val durationMs: Long get() = _durationMs.get()

    private val _frameCount = AtomicInteger(0)
    private val _durationMs = AtomicLong(0L)

    // ── Internals ────────────────────────────────────────────────────────────

    private val robot = Robot()
    private val scope = CoroutineScope(Dispatchers.IO)
    private var captureJob: Job? = null
    private var captureRect: Rectangle? = null
    private var framesDir: File? = null
    private val narrationMutex = Mutex()
    private val narrationFrames = mutableListOf<NarrationEntry>() // frame# → text

    data class NarrationEntry(val frameStart: Int, val frameEnd: Int, val text: String)

    private var currentNarration: String = ""
    private var narrationStartFrame: Int = 0

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Start recording the area occupied by [window].
     * Must be called from any thread; window bounds are read at start.
     */
    fun startRecording(window: java.awt.Window? = null, region: Rectangle? = null) {
        check(state == State.IDLE) { "Already recording" }
        captureRect = when {
            window != null -> Rectangle(window.locationOnScreen.x, window.locationOnScreen.y,
                window.width, window.height)
            region != null -> region
            else -> {
                val screen = Toolkit.getDefaultToolkit().screenSize
                Rectangle(0, 0, screen.width, screen.height)
            }
        }
        framesDir = createTempFramesDir()
        _frameCount.set(0)
        _durationMs.set(0L)
        narrationFrames.clear()
        currentNarration = ""
        state = State.RECORDING
        val intervalMs = 1000L / fps
        val startMs = System.currentTimeMillis()
        captureJob = scope.launch {
            while (isActive) {
                val t0 = System.currentTimeMillis()
                captureFrame()
                _durationMs.set(System.currentTimeMillis() - startMs)
                val elapsed = System.currentTimeMillis() - t0
                val wait = intervalMs - elapsed
                if (wait > 0) delay(wait)
            }
        }
    }

    /**
     * Set the narration text to burn into subsequent frames.
     * Empty string clears the subtitle.
     */
    fun setNarration(text: String) {
        if (state != State.RECORDING) return
        val now = _frameCount.get()
        scope.launch {
            narrationMutex.withLock {
                if (currentNarration.isNotEmpty()) {
                    narrationFrames += NarrationEntry(narrationStartFrame, now - 1, currentNarration)
                }
                currentNarration = text
                narrationStartFrame = now
            }
        }
    }

    /**
     * Stop recording and assemble frames into an MP4 at [outputFile].
     * Returns the output file path on success.
     * Throws [IOException] if ffmpeg fails.
     */
    fun stopAndExport(outputFile: File): File {
        check(state == State.RECORDING) { "Not recording" }
        captureJob?.cancel()
        captureJob = null
        // Close final narration entry
        val finalFrame = _frameCount.get()
        if (currentNarration.isNotEmpty()) {
            narrationFrames += NarrationEntry(narrationStartFrame, finalFrame - 1, currentNarration)
        }
        state = State.EXPORTING

        val dir = framesDir ?: throw IllegalStateException("No frames directory")
        outputFile.parentFile?.mkdirs()

        try {
            buildFfmpegCommand(dir, outputFile).run()
        } finally {
            state = State.IDLE
        }
        return outputFile
    }

    /** Stop without exporting — discards frames. */
    fun stopDiscard() {
        captureJob?.cancel()
        captureJob = null
        framesDir?.deleteRecursively()
        framesDir = null
        state = State.IDLE
    }

    // ── Frame capture ────────────────────────────────────────────────────────

    private fun captureFrame() {
        val rect = captureRect ?: return
        val dir = framesDir ?: return
        try {
            val img: BufferedImage = robot.createScreenCapture(rect)
            val frameNum = _frameCount.getAndIncrement()
            val file = File(dir, "frame_%08d.png".format(frameNum))
            ImageIO.write(img, "PNG", file)
        } catch (_: Exception) {
            // Capture can fail transiently (e.g. window minimised) — skip frame
        }
    }

    // ── ffmpeg assembly ───────────────────────────────────────────────────────

    /**
     * Build the ffmpeg command that:
     *  - reads PNG frames at [fps]
     *  - burns in narration subtitles via drawtext
     *  - outputs H.264 / AAC-silent MP4 at [outputFile]
     */
    private fun buildFfmpegCommand(framesDir: File, outputFile: File): ProcessBuilder {
        val drawtextFilter = buildDrawtextFilter()
        val vf = if (drawtextFilter.isNotEmpty()) "-vf \"$drawtextFilter\"" else ""

        // Build arg list manually to avoid shell quoting issues
        val args = mutableListOf(
            ffmpegPath,
            "-y",                              // overwrite output
            "-framerate", fps.toString(),
            "-i", "${framesDir.absolutePath}/frame_%08d.png",
            "-c:v", "libx264",
            "-preset", "fast",
            "-crf", "18",                      // near-lossless
            "-pix_fmt", "yuv420p",             // broad compatibility
        )

        if (drawtextFilter.isNotEmpty()) {
            args += listOf("-vf", drawtextFilter)
        }

        args += listOf(
            "-an",                             // no audio (silent walkthrough)
            "-movflags", "+faststart",         // web-playable
            outputFile.absolutePath,
        )

        val pb = ProcessBuilder(args)
            .redirectErrorStream(true)
            .inheritIO()
        pb.environment()["PATH"] = System.getenv("PATH") ?: "/usr/local/bin:/usr/bin:/bin:/opt/homebrew/bin"
        return pb
    }

    private fun buildDrawtextFilter(): String {
        if (narrationFrames.isEmpty()) return ""
        // Each entry becomes an enable= range with drawtext
        return narrationFrames.joinToString(",") { entry ->
            val startSec = entry.frameStart.toDouble() / fps
            val endSec   = (entry.frameEnd + 1).toDouble() / fps
            val escaped  = entry.text
                .replace("'", "\\'")
                .replace(":", "\\:")
                .replace("\\", "\\\\")
            "drawtext=enable='between(t,${startSec},${endSec})':" +
                "text='$escaped':" +
                "fontcolor=white:" +
                "fontsize=28:" +
                "box=1:boxcolor=black@0.65:boxborderw=8:" +
                "x=(w-text_w)/2:y=h-th-32"
        }
    }

    private fun ProcessBuilder.run() {
        val process = start()
        val exitCode = process.waitFor()
        if (exitCode != 0) throw IOException("ffmpeg exited with code $exitCode")
    }

    // ── Utility ───────────────────────────────────────────────────────────────

    private fun createTempFramesDir(): File {
        val dir = File(System.getProperty("java.io.tmpdir"), "forge-recording-${System.currentTimeMillis()}")
        dir.mkdirs()
        return dir
    }
}

// ── Global singleton shared by the UI ────────────────────────────────────────

object ForgeRecorder {
    val recorder = ScreenRecorder()

    /** Live recording stats for UI display. */
    val isRecording: Boolean get() = recorder.state == ScreenRecorder.State.RECORDING
    val isExporting: Boolean get() = recorder.state == ScreenRecorder.State.EXPORTING
    val frameCount: Int    get() = recorder.frameCount
    val durationMs: Long   get() = recorder.durationMs

    /** Last exported video path (for open-in-finder). */
    @Volatile var lastExportPath: String? = null
}
