package com.lynxosretro.app

import android.app.Activity
import android.graphics.Color
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Native Nintendo 64 player (milestone M1 of the native-player project).
 *
 * Runs a prebuilt N64 libretro core (parallel_n64 by default,
 * mupen64plus_next as a per-game choice) as real ARM code through a
 * custom JNI bridge (lynxos_n64_jni.c) with EGL: the core renders
 * straight into this Activity's SurfaceView. libretro-kmp cannot run
 * N64 cores — it has no hardware-render support — so this player does
 * not use it. The WebView UI stays in charge of the library and cloud
 * sync; this Activity only plays one N64 game file, then returns to
 * the library.
 *
 * Controls: the remote is held sideways. The D-pad is the control
 * stick (and mirrors the D-pad buttons); channel-up is A, channel-down
 * is B, OK is Start, Back tap is Z; while Back is held the D-pad
 * becomes the C buttons and channel-up/channel-down become L/R; holding
 * Back alone for half a second leaves the game. A standard gamepad
 * (Switch controller) works with its own buttons, no rotation: left
 * stick is the stick, right stick is the C buttons, L2 is Z.
 *
 * In-game saves are stored as <title>.srm next to the PS1 saves, so
 * switching cores keeps them. The game pauses when the screen goes
 * away (Home, rotation) and resumes when it comes back.
 */
class NativeN64PlayerActivity : Activity(), SurfaceHolder.Callback {

    companion object {
        const val EXTRA_GAME_PATH = "gamePath"
        const val EXTRA_GAME_TITLE = "gameTitle"
        const val EXTRA_CORE = "core" // "parallel" or "mupen"
        private const val TAG = "LynxosN64"
        private const val CORE_PARALLEL_ASSET = "native/parallel_n64_libretro_android.so"
        private const val CORE_PARALLEL_NAME = "parallel_n64_libretro_android.so"
        private const val CORE_MUPEN_ASSET = "native/mupen64plus_next_gles3_libretro_android.so"
        private const val CORE_MUPEN_NAME = "mupen64plus_next_gles3_libretro_android.so"
        private const val CORE_VERSION = "1"
        private const val BACK_HOLD_EXIT_MS = 500L
        private const val Z_TAP_MS = 100L
        private const val STICK_MAX = 32767
        private const val STICK_DEAD_ZONE = 0.15f

        // RetroPad button ids (same numbering as libretro-kmp's
        // JoypadButton, and the ids the N64 cores expect).
        private const val JP_B = 0
        private const val JP_Y = 1
        private const val JP_SELECT = 2
        private const val JP_START = 3
        private const val JP_UP = 4
        private const val JP_DOWN = 5
        private const val JP_LEFT = 6
        private const val JP_RIGHT = 7
        private const val JP_A = 8
        private const val JP_X = 9
        private const val JP_L = 10
        private const val JP_R = 11
        private const val JP_L2 = 12
        private const val JP_R2 = 13

        init {
            System.loadLibrary("lynxos_n64_jni")
        }
    }

    private lateinit var surfaceView: SurfaceView
    private var audioTrack: AudioTrack? = null
    private var coreThread: Thread? = null
    private val running = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val surfaceReady = CountDownLatch(1)

    // Android keyCode -> RetroPad button id, for keys currently down
    // (gamepad buttons and remote non-D-pad buttons).
    private val downMap = mutableMapOf<Int, Int>()
    // Remote D-pad keyCodes currently held. The D-pad drives the stick
    // (and, while Back is held, the C buttons) through refreshDpad().
    private val remoteDpadHeld = mutableSetOf<Int>()
    // RetroPad buttons currently pressed by the D-pad, so refreshDpad
    // can release exactly those without touching gamepad buttons.
    private val dpadButtons = mutableSetOf<Int>()

    private var backHeld = false
    private var backDownTime = 0L
    private var backChordUsed = false
    private var gameTitle = "game"

    private var tts: TextToSpeech? = null
    private var pendingSpeak: String? = null

    private val exitRunnable = Runnable {
        if (backHeld && !backChordUsed) finishPlayer()
    }

    // ----- JNI bridge (lynxos_n64_jni.c) -----

    private external fun nativeInit(activity: NativeN64PlayerActivity): Boolean
    private external fun nativeSetSurface(surface: android.view.Surface?)
    private external fun nativeLoadCore(corePath: String, systemDir: String, saveDir: String): Boolean
    private external fun nativeSetOption(key: String, value: String)
    private external fun nativeLoadGame(gamePath: String, title: String): Boolean
    private external fun nativeRunFrame(): Boolean
    private external fun nativeGetFps(): Double
    private external fun nativeGetSampleRate(): Double
    private external fun nativeSetButton(id: Int, pressed: Boolean)
    private external fun nativeSetAnalog(index: Int, x: Int, y: Int)
    private external fun nativeGetLastError(): String
    private external fun nativeClose()

    /** Called from the native bridge on the core thread with one audio batch. */
    fun onAudioBatch(samples: ShortArray, frames: Int) {
        try {
            audioTrack?.write(samples, 0, frames * 2, AudioTrack.WRITE_BLOCKING)
        } catch (e: Exception) {
            Log.w(TAG, "audio write failed", e)
        }
    }

    // ----- Surface lifecycle -----

    override fun surfaceCreated(holder: SurfaceHolder) {
        nativeSetSurface(holder.surface)
        surfaceReady.countDown()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        nativeSetSurface(null)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        gameTitle = intent.getStringExtra(EXTRA_GAME_TITLE) ?: "game"
        val gamePath = intent.getStringExtra(EXTRA_GAME_PATH)
        val coreChoice = intent.getStringExtra(EXTRA_CORE) ?: "parallel"
        if (gamePath.isNullOrEmpty()) {
            Toast.makeText(this, "No game file to play.", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        if (!Build.SUPPORTED_ABIS.contains("arm64-v8a")) {
            Toast.makeText(this, "This device can't run Nintendo 64 games.", Toast.LENGTH_LONG).show()
            speak("This device can't run Nintendo 64 games.")
            finish()
            return
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)
        surfaceView = SurfaceView(this)
        surfaceView.setBackgroundColor(Color.BLACK)
        surfaceView.holder.addCallback(this)
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

        // The core thread does everything: load, run the frame loop,
        // and close the bridge when it ends. The EGL context lives on
        // this thread, so cleanup must happen here too.
        coreThread = Thread {
            try {
                if (!nativeInit(this)) {
                    throw IllegalStateException(lastErrorOr("The Nintendo 64 player could not start."))
                }
                if (!surfaceReady.await(5, TimeUnit.SECONDS)) {
                    throw IllegalStateException("The game screen was not ready.")
                }
                val coreFile = ensureCoreFile(coreChoice)
                val systemDir = File(filesDir, "native-system").apply { mkdirs() }.absolutePath
                val saveDir = File(filesDir, "native-saves").apply { mkdirs() }.absolutePath
                if (!nativeLoadCore(coreFile.absolutePath, systemDir, saveDir)) {
                    throw IllegalStateException(lastErrorOr("The Nintendo 64 game core could not be loaded."))
                }
                applyCoreOptions(coreChoice)
                if (!nativeLoadGame(gamePath, gameTitle)) {
                    throw IllegalStateException(lastErrorOr("The game could not be loaded."))
                }
                val fps = nativeGetFps().let { if (it > 0) it else 60.0 }
                val sampleRate = nativeGetSampleRate().let { if (it > 0) it.toInt() else 44100 }
                runOnUiThread { startAudio(sampleRate) }
                speak("Now playing $gameTitle, Nintendo 64. " + controlSummary())
                running.set(true)
                val frameNanos = (1_000_000_000.0 / fps).toLong()
                while (running.get()) {
                    val t0 = System.nanoTime()
                    if (!nativeRunFrame()) {
                        val reason = nativeGetLastError()
                        runOnUiThread {
                            if (reason.isNotEmpty()) {
                                Toast.makeText(this, "The game stopped.", Toast.LENGTH_LONG).show()
                                speak("The game stopped. $reason")
                            }
                            finishPlayer()
                        }
                        break
                    }
                    val spent = System.nanoTime() - t0
                    val sleepNanos = frameNanos - spent
                    if (sleepNanos > 0) {
                        try {
                            Thread.sleep(sleepNanos / 1_000_000, (sleepNanos % 1_000_000).toInt())
                        } catch (e: InterruptedException) {
                            break
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Could not start $gameTitle", e)
                val reason = lastErrorOr(e.message ?: "Could not start $gameTitle.")
                runOnUiThread {
                    Toast.makeText(this, "Could not start $gameTitle.", Toast.LENGTH_LONG).show()
                    speak("Could not start $gameTitle. $reason")
                    finishPlayer()
                }
            } finally {
                try {
                    nativeClose()
                } catch (e: Exception) {
                    Log.w(TAG, "native close failed", e)
                }
            }
        }
        coreThread?.start()
    }

    private fun lastErrorOr(fallback: String): String {
        return try {
            val reason = nativeGetLastError()
            if (reason.isNotEmpty()) reason else fallback
        } catch (e: Exception) {
            fallback
        }
    }

    private fun controlSummary(): String {
        return "Hold the remote sideways, top to the left. " +
            "The D-pad is the control stick, channel up is A, channel down is B. " +
            "Tap OK for Start. Tap Back for Z. Hold Back and press the D-pad for the C buttons, " +
            "channel up for L, channel down for R. " +
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
    private fun ensureCoreFile(coreChoice: String): File {
        val dir = File(filesDir, "native-cores")
        dir.mkdirs()
        val (asset, name) = if (coreChoice == "mupen") {
            CORE_MUPEN_ASSET to CORE_MUPEN_NAME
        } else {
            CORE_PARALLEL_ASSET to CORE_PARALLEL_NAME
        }
        val out = File(dir, name)
        val ver = File(dir, "version.txt")
        val current = try { ver.readText() } catch (e: Exception) { "" }
        if (!out.exists() || current != CORE_VERSION) {
            assets.open(asset).use { input ->
                FileOutputStream(out).use { output -> input.copyTo(output) }
            }
            ver.writeText(CORE_VERSION)
        }
        return out
    }

    /** Core options, set before loadGame (the cores only read them then).
     * Every key and value below was verified against the core's own
     * libretro_core_options.h and the shipped .so. */
    private fun applyCoreOptions(core: String) {
        if (core == "mupen") {
            nativeSetOption("mupen64plus-rdp-plugin", "gliden64")
            nativeSetOption("mupen64plus-rsp-plugin", "hle")
            nativeSetOption("mupen64plus-cpucore", "dynamic_recompiler")
            nativeSetOption("mupen64plus-43screensize", "640x480")
            nativeSetOption("mupen64plus-ThreadedRenderer", "False")
            nativeSetOption("mupen64plus-EnableFBEmulation", "False")
            nativeSetOption("mupen64plus-EnableLODEmulation", "False")
            nativeSetOption("mupen64plus-EnableCopyColorToRDRAM", "Off")
            nativeSetOption("mupen64plus-EnableCopyDepthToRDRAM", "Off")
            nativeSetOption("mupen64plus-CountPerOp", "1")
            nativeSetOption("mupen64plus-BilinearMode", "3point")
            nativeSetOption("mupen64plus-Framerate", "Original")
        } else {
            nativeSetOption("parallel-n64-gfxplugin", "glide64")
            nativeSetOption("parallel-n64-gfxplugin-accuracy", "low")
            nativeSetOption("parallel-n64-rspplugin", "hle")
            nativeSetOption("parallel-n64-cpucore", "dynamic_recompiler")
        }
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

    // ---------- Input: remote (sideways) and standard gamepad ----------

    private fun isGamepad(event: KeyEvent): Boolean {
        val src = event.source
        return (src and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
            (src and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
    }

    /** Remote key -> RetroPad button, applying the sideways rotation.
     * N64 A is JOYPAD B, N64 B is JOYPAD Y; N64 L is JOYPAD SELECT and
     * N64 R is JOYPAD R2. Back+OK is unmapped: nothing is left that
     * the D-pad chords do not already cover. */
    private fun mapRemoteKey(code: Int): Int? = when (code) {
        KeyEvent.KEYCODE_CHANNEL_UP -> if (backHeld) JP_SELECT else JP_B
        KeyEvent.KEYCODE_CHANNEL_DOWN -> if (backHeld) JP_R2 else JP_Y
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER ->
            if (backHeld) null else JP_START
        else -> null
    }

    /** Standard gamepad key -> RetroPad button, no rotation. The
     * Switch controller's A/B read swapped against their labels on
     * Android; a remap can fix that if it feels wrong. */
    private fun mapGamepadKey(code: Int): Int? = when (code) {
        KeyEvent.KEYCODE_BUTTON_A -> JP_B // N64 A
        KeyEvent.KEYCODE_BUTTON_B -> JP_Y // N64 B
        KeyEvent.KEYCODE_BUTTON_X -> JP_X // C-Up
        KeyEvent.KEYCODE_BUTTON_Y -> JP_A // C-Down
        KeyEvent.KEYCODE_BUTTON_START -> JP_START
        KeyEvent.KEYCODE_BUTTON_SELECT -> JP_SELECT // N64 L
        KeyEvent.KEYCODE_BUTTON_L1 -> JP_SELECT // N64 L
        KeyEvent.KEYCODE_BUTTON_R1 -> JP_R2 // N64 R
        KeyEvent.KEYCODE_BUTTON_L2 -> JP_L2 // N64 Z
        KeyEvent.KEYCODE_BUTTON_R2 -> null
        KeyEvent.KEYCODE_DPAD_UP -> JP_UP
        KeyEvent.KEYCODE_DPAD_DOWN -> JP_DOWN
        KeyEvent.KEYCODE_DPAD_LEFT -> JP_LEFT
        KeyEvent.KEYCODE_DPAD_RIGHT -> JP_RIGHT
        else -> null
    }

    /**
     * Recomputes everything the remote D-pad drives, from the keys
     * currently held and whether Back is held. The rotation is the
     * same either way: the direction that means "up" in the game is
     * the C-Up button when Back is held, and the stick's up when it
     * is not. Releases exactly the buttons the D-pad pressed before,
     * so gamepad buttons are never touched.
     */
    private fun refreshDpad() {
        for (id in dpadButtons) nativeSetButton(id, false)
        dpadButtons.clear()
        var sx = 0
        var sy = 0
        var cx = 0
        var cy = 0
        for (code in remoteDpadHeld) {
            if (backHeld) {
                when (code) {
                    KeyEvent.KEYCODE_DPAD_UP -> { dpadButtons.add(JP_L); cx = -STICK_MAX }
                    KeyEvent.KEYCODE_DPAD_DOWN -> { dpadButtons.add(JP_R); cx = STICK_MAX }
                    KeyEvent.KEYCODE_DPAD_LEFT -> { dpadButtons.add(JP_A); cy = STICK_MAX }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> { dpadButtons.add(JP_X); cy = -STICK_MAX }
                }
            } else {
                when (code) {
                    KeyEvent.KEYCODE_DPAD_UP -> { dpadButtons.add(JP_LEFT); sx = -STICK_MAX }
                    KeyEvent.KEYCODE_DPAD_DOWN -> { dpadButtons.add(JP_RIGHT); sx = STICK_MAX }
                    KeyEvent.KEYCODE_DPAD_LEFT -> { dpadButtons.add(JP_DOWN); sy = STICK_MAX }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> { dpadButtons.add(JP_UP); sy = -STICK_MAX }
                }
            }
        }
        for (id in dpadButtons) nativeSetButton(id, true)
        nativeSetAnalog(0, sx, sy)
        nativeSetAnalog(1, cx, cy)
    }

    private fun axisToStick(v: Float): Int {
        if (v > -STICK_DEAD_ZONE && v < STICK_DEAD_ZONE) return 0
        return (v * STICK_MAX).toInt().coerceIn(-STICK_MAX, STICK_MAX)
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        val src = event.source
        val isStick = (src and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK ||
            (src and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
        if (isStick && event.action == MotionEvent.ACTION_MOVE) {
            val lx = axisToStick(event.getAxisValue(MotionEvent.AXIS_X))
            val ly = axisToStick(event.getAxisValue(MotionEvent.AXIS_Y))
            val rx = axisToStick(event.getAxisValue(MotionEvent.AXIS_Z))
            val ry = axisToStick(event.getAxisValue(MotionEvent.AXIS_RZ))
            nativeSetAnalog(0, lx, ly)
            nativeSetAnalog(1, rx, ry)
            return true
        }
        return super.onGenericMotionEvent(event)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        if (code == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                backHeld = true
                backChordUsed = false
                // D-pad keys already held switch to C buttons the
                // moment Back goes down: that is a chord, not a tap.
                if (remoteDpadHeld.isNotEmpty()) backChordUsed = true
                backDownTime = System.currentTimeMillis()
                refreshDpad()
                mainHandler.postDelayed(exitRunnable, BACK_HOLD_EXIT_MS)
            } else if (event.action == KeyEvent.ACTION_UP) {
                mainHandler.removeCallbacks(exitRunnable)
                val heldMs = System.currentTimeMillis() - backDownTime
                backHeld = false
                refreshDpad()
                if (!backChordUsed && heldMs < BACK_HOLD_EXIT_MS) {
                    // Tap Back = Z: hold it for one or two frames.
                    nativeSetButton(JP_L2, true)
                    mainHandler.postDelayed({ nativeSetButton(JP_L2, false) }, Z_TAP_MS)
                }
                backChordUsed = false
            }
            return true
        }
        if (isGamepad(event)) {
            val button = mapGamepadKey(code)
            if (button != null) {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                    if (backHeld) backChordUsed = true
                    downMap[code] = button
                    nativeSetButton(button, true)
                } else if (event.action == KeyEvent.ACTION_UP) {
                    val held = downMap.remove(code)
                    if (held != null) nativeSetButton(held, false)
                }
                return true
            }
            return super.dispatchKeyEvent(event)
        }
        if (code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN ||
            code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                if (backHeld) backChordUsed = true
                remoteDpadHeld.add(code)
                refreshDpad()
            } else if (event.action == KeyEvent.ACTION_UP) {
                remoteDpadHeld.remove(code)
                refreshDpad()
            }
            return true
        }
        val button = mapRemoteKey(code)
        if (button != null) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                if (backHeld) backChordUsed = true
                downMap[code] = button
                nativeSetButton(button, true)
            } else if (event.action == KeyEvent.ACTION_UP) {
                val held = downMap.remove(code)
                if (held != null) nativeSetButton(held, false)
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun finishPlayer() {
        if (closed.getAndSet(true)) return
        running.set(false)
        mainHandler.removeCallbacks(exitRunnable)
        try {
            coreThread?.join(1000)
        } catch (e: Exception) {
        }
        try {
            audioTrack?.stop()
        } catch (e: Exception) {
        }
        try {
            audioTrack?.release()
        } catch (e: Exception) {
        }
        audioTrack = null
        try {
            tts?.stop()
        } catch (e: Exception) {
        }
        try {
            tts?.shutdown()
        } catch (e: Exception) {
        }
        tts = null
        finish()
    }

    override fun onDestroy() {
        finishPlayer()
        super.onDestroy()
    }

    // Back is a game button (Z) here, handled in dispatchKeyEvent.
    // Never let the system finish this Activity on a Back tap.
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
    }
}
