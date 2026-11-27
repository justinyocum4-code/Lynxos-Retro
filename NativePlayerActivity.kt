package com.lynxosretro.app

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.SurfaceView
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import dev.kotlinds.libretrokmp.Device
import dev.kotlinds.libretrokmp.JoypadButton
import dev.kotlinds.libretrokmp.LibretroCore
import dev.kotlinds.libretrokmp.LibretroFrontend
import dev.kotlinds.libretrokmp.LogLevel
import dev.kotlinds.libretrokmp.VideoFrame
import java.io.File
import java.io.FileOutputStream
import java.util.Collections
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Native PlayStation player (milestone 1 of the native-player project).
 *
 * Runs the prebuilt pcsx_rearmed libretro core as real ARM code through
 * libretro-kmp (bundled JNI bridge, no NDK build on our side). The WebView
 * UI stays in charge of the library and cloud sync; this Activity only
 * plays one PS1 game file, then returns to the library.
 *
 * Controls mirror the WebView player exactly: the remote is held sideways,
 * channel-up is Cross, channel-down is Circle, OK is Start, Back tap is
 * Triangle, Back-held chords give Square / L1 / R1, and holding Back alone
 * for half a second leaves the game. A standard gamepad (Switch controller)
 * works with its own buttons, no rotation.
 */
class NativePlayerActivity : Activity() {

    companion object {
        const val EXTRA_GAME_PATH = "gamePath"
        const val EXTRA_GAME_TITLE = "gameTitle"
        private const val TAG = "NativePlayer"
        private const val CORE_ASSET = "native/pcsx_rearmed_libretro_android.so"
        private const val CORE_NAME = "pcsx_rearmed_libretro_android.so"
        private const val CORE_VERSION = "1"
        private const val BACK_HOLD_EXIT_MS = 500L
        private const val START_WATCHDOG_MS = 30_000L
        private const val TRIANGLE_TAP_MS = 100L
    }

    private lateinit var surfaceView: SurfaceView
    private var core: LibretroCore? = null
    private var audioTrack: AudioTrack? = null
    private var loopThread: Thread? = null
    private val running = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())

    // Buttons currently held, by RetroPad id. Guarded by itself.
    private val pressed = Collections.synchronizedSet(mutableSetOf<Int>())
    // Android keyCode -> RetroPad button id, for keys currently down.
    private val downMap = mutableMapOf<Int, Int>()

    private var backHeld = false
    private var backDownTime = 0L
    private var backChordUsed = false
    private var gameTitle = "game"

    private var tts: TextToSpeech? = null
    private var pendingSpeak: String? = null

    private var frameBitmap: Bitmap? = null
    private val paint = Paint().apply { isFilterBitmap = false }

    private val exitRunnable = Runnable {
        if (backHeld && !backChordUsed) finishPlayer()
    }

    // Start watchdog, same shape as the N64 player: if the frame
    // loop has not started 30 s after the core thread starts, the
    // thread is hung inside a blocking loadGame call (a hang is not
    // an exception, so the try/catch never fires). The finish is
    // delayed 5 s after the spoken warning and guarded on running
    // still being false: finishPlayer stops TTS, so finishing
    // immediately would cut the warning off unheard.
    private val startWatchdog = Runnable {
        if (!running.get()) {
            speak("The PlayStation game is taking too long to start.")
            mainHandler.postDelayed({
                if (!running.get()) finishPlayer()
            }, 5000L)
        }
    }

    private val frontend = object : LibretroFrontend {
        override val systemDirectory: String
            get() = File(filesDir, "native-system").apply { mkdirs() }.absolutePath
        override val saveDirectory: String
            get() = File(filesDir, "native-saves").apply { mkdirs() }.absolutePath

        // PS1 speed options, same values already tuned in the browser
        // player (verified in the bundled core): frameskip on auto with
        // a 45% threshold, and the instruction-cache emulation off.
        // Everything else keeps the core's default.
        override fun variable(key: String): String? = when (key) {
            "pcsx_rearmed_frameskip" -> "auto_threshold"
            "pcsx_rearmed_frameskip_threshold" -> "45"
            "pcsx_rearmed_frameskip_interval" -> "3"
            "pcsx_rearmed_icache_emulation" -> "disabled"
            else -> null
        }

        override fun onVideoFrame(frame: VideoFrame) {
            var bmp = frameBitmap
            if (bmp == null || bmp.width != frame.width || bmp.height != frame.height) {
                bmp = Bitmap.createBitmap(frame.width, frame.height, Bitmap.Config.ARGB_8888)
                frameBitmap = bmp
            }
            bmp.setPixels(frame.pixels, 0, frame.width, 0, 0, frame.width, frame.height)
            drawFrame(bmp)
        }

        override fun onAudio(samples: ShortArray, frames: Int) {
            try {
                audioTrack?.write(samples, 0, frames * 2, AudioTrack.WRITE_BLOCKING)
            } catch (e: Exception) {
                Log.w(TAG, "audio write failed", e)
            }
        }

        override fun inputState(port: Int, device: Int, index: Int, id: Int): Short {
            if (port != 0 || device != Device.JOYPAD) return 0
            return if (pressed.contains(id)) 1 else 0
        }

        override fun onLog(level: LogLevel, message: String) {
            Log.i(TAG, "core [$level] $message")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        gameTitle = intent.getStringExtra(EXTRA_GAME_TITLE) ?: "game"
        val gamePath = intent.getStringExtra(EXTRA_GAME_PATH)
        if (gamePath.isNullOrEmpty()) {
            Toast.makeText(this, "No game file to play.", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)
        surfaceView = SurfaceView(this)
        surfaceView.setBackgroundColor(Color.BLACK)
        root.addView(surfaceView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        setContentView(root)

        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.US
                pendingSpeak?.let { text ->
                    pendingSpeak = null
                    tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "native-start")
                }
            }
        }

        Thread {
            try {
                val coreFile = ensureCoreFile()
                val c = LibretroCore(coreFile.absolutePath, frontend)
                core = c
                c.loadGame(gamePath)
                c.setControllerPortDevice(0, Device.JOYPAD)
                val fps = c.avInfo.timing.fps
                val sampleRate = c.avInfo.timing.sampleRate.toInt()
                runOnUiThread { startAudio(sampleRate) }
                speak("Now playing $gameTitle, PlayStation. " + controlSummary())
                startLoop(fps)
            } catch (e: Exception) {
                Log.e(TAG, "Could not start $gameTitle", e)
                runOnUiThread {
                    Toast.makeText(this, "Could not start $gameTitle.", Toast.LENGTH_LONG).show()
                    speak("Could not start $gameTitle.")
                    finish()
                }
            }
        }.start()
        mainHandler.postDelayed(startWatchdog, START_WATCHDOG_MS)
    }

    private fun controlSummary(): String {
        return "Hold the remote sideways, top to the left. " +
            "Channel up is Cross, channel down is Circle. Tap OK for Start. " +
            "Tap Back is Triangle. Hold Back and press OK for Square, " +
            "channel up for L1, channel down for R1. " +
            "Hold Back alone for half a second to leave."
    }

    private fun speak(text: String) {
        val t = tts
        if (t != null) {
            try {
                t.speak(text, TextToSpeech.QUEUE_FLUSH, null, "native-start")
                return
            } catch (e: Exception) {
                Log.w(TAG, "speak failed", e)
            }
        }
        pendingSpeak = text
    }

    /** Copies the bundled core .so from assets to internal storage (dlopen needs a real file). */
    private fun ensureCoreFile(): File {
        val dir = File(filesDir, "native-cores")
        dir.mkdirs()
        val out = File(dir, CORE_NAME)
        val ver = File(dir, "version.txt")
        val current = try { ver.readText() } catch (e: Exception) { "" }
        if (!out.exists() || current != CORE_VERSION) {
            assets.open(CORE_ASSET).use { input ->
                FileOutputStream(out).use { output -> input.copyTo(output) }
            }
            ver.writeText(CORE_VERSION)
        }
        return out
    }

    private fun startAudio(sampleRate: Int) {
        try {
            val minBuf = AudioTrack.getMinBufferSize(
                sampleRate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build())
                .setAudioFormat(AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build())
                .setBufferSizeInBytes(minBuf * 4)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            audioTrack?.play()
        } catch (e: Exception) {
            Log.w(TAG, "Could not start audio", e)
        }
    }

    private fun startLoop(fps: Double) {
        running.set(true)
        mainHandler.removeCallbacks(startWatchdog)
        val frameNanos = (1_000_000_000.0 / fps).toLong()
        loopThread = Thread {
            val c = core ?: return@Thread
            while (running.get()) {
                val t0 = System.nanoTime()
                try {
                    c.run()
                } catch (e: Exception) {
                    Log.e(TAG, "core run failed", e)
                    runOnUiThread {
                        Toast.makeText(this, "The game stopped.", Toast.LENGTH_LONG).show()
                        finishPlayer()
                    }
                    return@Thread
                }
                val spent = System.nanoTime() - t0
                val sleepNanos = frameNanos - spent
                if (sleepNanos > 0) {
                    try {
                        Thread.sleep(sleepNanos / 1_000_000, (sleepNanos % 1_000_000).toInt())
                    } catch (e: InterruptedException) {
                        return@Thread
                    }
                }
            }
        }
        loopThread?.start()
    }

    private fun drawFrame(bmp: Bitmap) {
        val surface = surfaceView.holder.surface
        if (!surface.isValid) return
        val canvas: Canvas = try {
            surfaceView.holder.lockCanvas()
        } catch (e: Exception) {
            return
        } ?: return
        try {
            canvas.drawColor(Color.BLACK)
            val sw = canvas.width.toFloat()
            val sh = canvas.height.toFloat()
            val scale = minOf(sw / bmp.width, sh / bmp.height)
            val dw = bmp.width * scale
            val dh = bmp.height * scale
            val dx = (sw - dw) / 2f
            val dy = (sh - dh) / 2f
            canvas.drawBitmap(bmp, null,
                Rect(dx.toInt(), dy.toInt(), (dx + dw).toInt(), (dy + dh).toInt()), paint)
        } finally {
            surfaceView.holder.unlockCanvasAndPost(canvas)
        }
    }

    // ---------- Input: remote (sideways) and standard gamepad ----------

    private fun isGamepad(event: KeyEvent): Boolean {
        val src = event.source
        return (src and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
            (src and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
    }

    /** Remote key -> RetroPad button, applying the sideways rotation. */
    private fun mapRemoteKey(code: Int): Int? = when (code) {
        KeyEvent.KEYCODE_DPAD_UP -> JoypadButton.LEFT
        KeyEvent.KEYCODE_DPAD_DOWN -> JoypadButton.RIGHT
        KeyEvent.KEYCODE_DPAD_LEFT -> JoypadButton.DOWN
        KeyEvent.KEYCODE_DPAD_RIGHT -> JoypadButton.UP
        KeyEvent.KEYCODE_CHANNEL_UP -> if (backHeld) JoypadButton.L else JoypadButton.A
        KeyEvent.KEYCODE_CHANNEL_DOWN -> if (backHeld) JoypadButton.R else JoypadButton.B
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER ->
            if (backHeld) JoypadButton.Y else JoypadButton.START
        else -> null
    }

    /** Standard gamepad key -> RetroPad button, no rotation. */
    private fun mapGamepadKey(code: Int): Int? = when (code) {
        KeyEvent.KEYCODE_BUTTON_A -> JoypadButton.A
        KeyEvent.KEYCODE_BUTTON_B -> JoypadButton.B
        KeyEvent.KEYCODE_BUTTON_X -> JoypadButton.X
        KeyEvent.KEYCODE_BUTTON_Y -> JoypadButton.Y
        KeyEvent.KEYCODE_BUTTON_START -> JoypadButton.START
        KeyEvent.KEYCODE_BUTTON_SELECT -> JoypadButton.SELECT
        KeyEvent.KEYCODE_BUTTON_L1 -> JoypadButton.L
        KeyEvent.KEYCODE_BUTTON_R1 -> JoypadButton.R
        KeyEvent.KEYCODE_BUTTON_L2 -> JoypadButton.L2
        KeyEvent.KEYCODE_BUTTON_R2 -> JoypadButton.R2
        KeyEvent.KEYCODE_DPAD_UP -> JoypadButton.UP
        KeyEvent.KEYCODE_DPAD_DOWN -> JoypadButton.DOWN
        KeyEvent.KEYCODE_DPAD_LEFT -> JoypadButton.LEFT
        KeyEvent.KEYCODE_DPAD_RIGHT -> JoypadButton.RIGHT
        else -> null
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        if (code == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                backHeld = true
                backChordUsed = false
                backDownTime = System.currentTimeMillis()
                mainHandler.postDelayed(exitRunnable, BACK_HOLD_EXIT_MS)
            } else if (event.action == KeyEvent.ACTION_UP) {
                mainHandler.removeCallbacks(exitRunnable)
                val heldMs = System.currentTimeMillis() - backDownTime
                backHeld = false
                if (!backChordUsed && heldMs < BACK_HOLD_EXIT_MS) {
                    // Tap Back = Triangle: hold it for one or two frames.
                    pressed.add(JoypadButton.X)
                    mainHandler.postDelayed({ pressed.remove(JoypadButton.X) }, TRIANGLE_TAP_MS)
                }
                backChordUsed = false
            }
            return true
        }
        val button = if (isGamepad(event)) mapGamepadKey(code) else mapRemoteKey(code)
        if (button != null) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                if (backHeld) backChordUsed = true
                downMap[code] = button
                pressed.add(button)
            } else if (event.action == KeyEvent.ACTION_UP) {
                val held = downMap.remove(code)
                if (held != null) pressed.remove(held)
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun finishPlayer() {
        if (closed.getAndSet(true)) return
        running.set(false)
        mainHandler.removeCallbacks(exitRunnable)
        mainHandler.removeCallbacks(startWatchdog)
        try { loopThread?.join(1000) } catch (e: Exception) { }
        try { core?.close() } catch (e: Exception) { Log.w(TAG, "core close failed", e) }
        core = null
        try { audioTrack?.stop() } catch (e: Exception) { }
        try { audioTrack?.release() } catch (e: Exception) { }
        audioTrack = null
        try { tts?.stop() } catch (e: Exception) { }
        try { tts?.shutdown() } catch (e: Exception) { }
        tts = null
        finish()
    }

    override fun onDestroy() {
        finishPlayer()
        super.onDestroy()
    }

    // Back is a game button (Triangle) here, handled in dispatchKeyEvent.
    // Never let the system finish this Activity on a Back tap.
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
    }
}
