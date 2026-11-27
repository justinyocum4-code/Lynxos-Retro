package com.lynxosretro.app

import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RelativeLayout
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
 * stick is the stick, right stick is the C buttons, L2 is Z. On a
 * touchscreen device (the tablet) there is also an on-screen touch
 * controller: D-pad bottom-left (stick + D-pad), A and B bottom-right,
 * C buttons above them, Start bottom-centre, L, Z and R across the
 * top. It is hidden on the TV, which has no touchscreen.
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
        private const val START_WATCHDOG_MS = 30_000L
        private const val VIDEO_CHECK_MS = 5_000L
        private const val Z_TAP_MS = 100L
        // A TalkBack double-tap (click) cannot hold a button, so it
        // does a short timed tap instead of a hold.
        private const val TOUCH_TAP_MS = 150L
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
    private lateinit var rootLayout: FrameLayout

    // The game's picture shape (width / height), from the core's AV
    // geometry once the game is loaded. Starts at 4:3 — the shape of
    // nearly every N64 game — so the first fit (before the core
    // loads) is already right in practice. Written on the core
    // thread, read on the UI thread.
    @Volatile
    private var gameAspect = 4f / 3f
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
    // Touch D-pad directions currently held, as the same
    // KEYCODE_DPAD_* tokens refreshDpad reads. The tablet is held
    // upright, so the top touch button feeds the token for game Up
    // (KEYCODE_DPAD_RIGHT in the sideways remote map). The union
    // with remoteDpadHeld means holding the same direction on both
    // inputs keeps it held until both let go.
    private val touchDpadHeld = mutableSetOf<Int>()
    // C buttons currently held by touch, and the ones the core
    // already has pressed, so refreshTouchC releases exactly those.
    private val touchCHeld = mutableSetOf<Int>()
    private val touchCActive = mutableSetOf<Int>()
    // RetroPad buttons currently pressed by the D-pad, so refreshDpad
    // can release exactly those without touching gamepad buttons.
    private val dpadButtons = mutableSetOf<Int>()

    private var backHeld = false
    private var backDownTime = 0L
    private var backChordUsed = false
    private var gameTitle = "game"

    private var tts: TextToSpeech? = null
    private var pendingSpeak: String? = null

    // Which load stage the core thread is in right now. The start
    // watchdog names it, so the next test pinpoints the hang stage
    // without logcat (init / screen / core copy / core load /
    // options / game load). Volatile: written on the core thread,
    // read on the main thread by the watchdog.
    @Volatile
    private var loadStage = "starting the player"

    // Load-stage breadcrumb that survives a process death: the
    // in-memory loadStage dies with the process, so each stage is
    // also written to SharedPreferences. If the player dies
    // silently, MainActivity reads it back and the "closed"
    // announcement can name the stage it died in.
    private fun setLoadStage(stage: String) {
        loadStage = stage
        Log.i(TAG, "load stage: $stage")
        try {
            getSharedPreferences("lynxos-retro", MODE_PRIVATE).edit()
                .putString("playerLoadStage", stage).apply()
        } catch (e: Exception) {
        }
    }

    /* Bridge-step breadcrumb (v1.4.7): the native side reports the
     * last graphics-setup step during retro_load_game; it is written
     * with commit() so a native crash milliseconds later still
     * leaves a durable trace naming the step it died in. Called from
     * native code via JNI, not from JavaScript — no annotation. */
    fun onBridgeStep(step: String) {
        try {
            getSharedPreferences("lynxos-retro", MODE_PRIVATE).edit()
                .putString("playerBridgeStep", step).commit()
        } catch (e: Exception) {}
    }

    /* F3 (v1.4.7): finishPlayer() stops and shuts down TTS, which
     * cancels any utterance speak() just queued. Delay the finish so
     * the spoken "Could not start ..." / "The game stopped" message
     * actually plays. Posted on mainHandler and cancelled in
     * finishPlayer(), so it cannot leak the Activity past its end. */
    private val finishPlayerRunnable = Runnable { finishPlayer() }

    private fun finishPlayerDelayed() {
        mainHandler.postDelayed(finishPlayerRunnable, 2500)
    }
    private var videoCheckDone = false

    private val exitRunnable = Runnable {
        if (backHeld && !backChordUsed) finishPlayer()
    }

    // Start watchdog: if the frame loop has not started 30 s after
    // the core thread starts, the core thread is hung inside a
    // blocking JNI call (nativeInit / nativeLoadCore / nativeLoadGame
    // — a hang is not an exception, so the try/catch never fires).
    // The UI thread is not blocked by that hang, so this still fires.
    // The finish is delayed 5 s after the spoken warning and guarded
    // on running still being false: finishPlayer stops and shuts
    // down TTS, so finishing immediately would cut the warning off
    // before it is heard, and a game that starts in those 5 s must
    // not be killed. The warning names the load stage the thread is
    // stuck in, and the delayed finish reports it back to the page
    // (the player's own speech may be silent on the TV).
    private val startWatchdog = Runnable {
        if (!running.get()) {
            speakAndToast("The Nintendo 64 game is taking too long to start. Still waiting: $loadStage.")
            mainHandler.postDelayed({
                if (!running.get()) {
                    reportOutcome("hang", "Still waiting: $loadStage.", hard = true)
                    finishPlayer()
                }
            }, 5000L)
        }
    }

    // Video check, 5 s after the frame loop starts: a zero presented
    // count here means the game runs but nothing reaches the screen.
    // The two native counters name the layer: no video callbacks at
    // all means the core is not producing frames; callbacks without
    // a presented frame means the screen is not ready. Fires once.
    private val videoCheck = Runnable {
        if (!videoCheckDone) {
            videoCheckDone = true
            val callbacks = try { nativeGetVideoCallbacks() } catch (e: Throwable) { -1L }
            val presented = try { nativeGetFramesPresented() } catch (e: Throwable) { -1L }
            if (presented == 0L) {
                val reason = if (callbacks == 0L)
                    "No video frames from the game."
                else
                    "The game screen is not ready."
                speakAndToast("The Nintendo 64 game is running but no picture has been drawn yet. $reason")
                reportOutcome("novideo", reason, hard = false)
                Log.w(TAG, "video check: callbacks=$callbacks presented=$presented")
            } else {
                Log.i(TAG, "video check: callbacks=$callbacks presented=$presented")
            }
        }
    }

    /** Speak AND show a Toast: the TV's own text-to-speech may be
     * silent, so speech alone can vanish unheard. TalkBack reads
     * Toasts aloud, so the Toast is the channel that still works. */
    private fun speakAndToast(text: String) {
        speak(text)
        runOnUiThread {
            try { Toast.makeText(this, text, Toast.LENGTH_LONG).show() } catch (e: Exception) { }
        }
    }

    /** Report this player's outcome back to MainActivity so the page
     * can announce it through TalkBack when the player closes (the
     * player's own speech may be silent on the TV). Soft outcomes
     * (the video warning) only stick when nothing is set yet; hard
     * ones (failures, stops, hangs) overwrite. MainActivity owns
     * the rule; this just forwards. */
    private fun reportOutcome(kind: String, reason: String, hard: Boolean) {
        try { MainActivity.reportPlayerOutcome("n64", kind, reason, hard) } catch (e: Exception) {
            Log.w(TAG, "report outcome failed", e)
        }
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
    private external fun nativeGetAspectRatio(): Float
    private external fun nativeSetButton(id: Int, pressed: Boolean)
    private external fun nativeSetAnalog(index: Int, x: Int, y: Int)
    private external fun nativeGetLastError(): String
    private external fun nativeGetVideoCallbacks(): Long
    private external fun nativeGetFramesPresented(): Long
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
        // The screen size changed (rotation / resize): re-fit the
        // game surface to the screen. The EGL window surface tracks
        // the SurfaceView's size, so no context recreation is needed.
        fitGameSurface()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        nativeSetSurface(null)
    }

    /** Size the game SurfaceView to the game's picture shape, fitted
     * inside the screen and centered (v1.4.10). The core draws its
     * own 4:3 frame bottom-anchored into whatever window it is
     * given, so a full-screen portrait window left the picture at
     * the bottom with black above it. A window already shaped to
     * the picture fills completely, and centering it puts the black
     * bars evenly around it instead of all on one side. The fit
     * runs early (before the core loads) so the EGL surface is
     * already the right size when the core's GL init runs; it runs
     * again when the game loads (real aspect from AV geometry) and
     * on every surface size change. The touch overlay is positioned
     * against the full screen and is untouched by this — only the
     * SurfaceView moves. */
    private fun fitGameSurface() {
        if (!::rootLayout.isInitialized || !::surfaceView.isInitialized) return
        val sw = rootLayout.width
        val sh = rootLayout.height
        if (sw <= 0 || sh <= 0) return
        val aspect = if (gameAspect > 0) gameAspect else 4f / 3f
        // Fit inside the screen: width-limited when the screen is
        // narrower than the picture shape, height-limited otherwise.
        val w: Int
        val h: Int
        if (sw.toFloat() / sh.toFloat() > aspect) {
            h = sh
            w = (sh * aspect).toInt()
        } else {
            w = sw
            h = (sw / aspect).toInt()
        }
        val lp = surfaceView.layoutParams as? FrameLayout.LayoutParams
        if (lp != null && lp.width == w && lp.height == h) return
        surfaceView.layoutParams = FrameLayout.LayoutParams(w, h, Gravity.CENTER)
        Log.i(TAG, "fit game surface ${w}x$h in ${sw}x$sh (aspect $aspect)")
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
        rootLayout = root
        root.setBackgroundColor(Color.BLACK)
        surfaceView = SurfaceView(this)
        // NO background on the SurfaceView itself: an opaque
        // background clears SKIP_DRAW, so the view no longer punches
        // its hole in the window and the EGL window surface below
        // stays hidden behind opaque black (same defect as the
        // PlayStation player's sound-with-no-picture). The root's
        // black is fine — only the SurfaceView's own background
        // kills the hole.
        surfaceView.holder.addCallback(this)
        root.addView(surfaceView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        setContentView(root)
        addTouchOverlay(root)
        // First fit, as soon as the screen is measured: the core's
        // GL init runs during load, so the surface must already be
        // the right size by then. surfaceChanged re-fits as well,
        // so a not-yet-measured root here is harmless.
        root.post { fitGameSurface() }

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
        // this thread, so cleanup must happen here too. Each step
        // updates loadStage (logged here, named by the watchdog)
        // so a hang is diagnosable by stage, not just by silence.
        coreThread = Thread {
            try {
                setLoadStage("starting the player")
                if (!nativeInit(this)) {
                    throw IllegalStateException(lastErrorOr("The Nintendo 64 player could not start."))
                }
                setLoadStage("waiting for the game screen")
                if (!surfaceReady.await(5, TimeUnit.SECONDS)) {
                    throw IllegalStateException("The game screen was not ready.")
                }
                setLoadStage("copying the game core")
                val coreFile = ensureCoreFile(coreChoice)
                val systemDir = File(filesDir, "native-system").apply { mkdirs() }.absolutePath
                val saveDir = File(filesDir, "native-saves").apply { mkdirs() }.absolutePath
                setLoadStage("loading the game core")
                if (!nativeLoadCore(coreFile.absolutePath, systemDir, saveDir)) {
                    throw IllegalStateException(lastErrorOr("The Nintendo 64 game core could not be loaded."))
                }
                setLoadStage("setting game options")
                applyCoreOptions(coreChoice)
                setLoadStage("loading the game")
                if (!nativeLoadGame(gamePath, gameTitle)) {
                    throw IllegalStateException(lastErrorOr("The game could not be loaded."))
                }
                // The game's real picture shape is known now (AV
                // geometry); re-fit the screen to it. Most games are
                // 4:3, the default used for the first fit, so this
                // usually changes nothing.
                try {
                    val aspect = nativeGetAspectRatio()
                    if (aspect > 0) gameAspect = aspect
                } catch (e: Exception) {
                    Log.w(TAG, "aspect read failed", e)
                }
                runOnUiThread { fitGameSurface() }
                val fps = nativeGetFps().let { if (it > 0) it else 60.0 }
                val sampleRate = nativeGetSampleRate().let { if (it > 0) it.toInt() else 44100 }
                runOnUiThread { startAudio(sampleRate) }
                speak("Now playing $gameTitle, Nintendo 64. " + controlSummary())
                running.set(true)
                setLoadStage("running")
                mainHandler.removeCallbacks(startWatchdog)
                mainHandler.postDelayed(videoCheck, VIDEO_CHECK_MS)
                val frameNanos = (1_000_000_000.0 / fps).toLong()
                while (running.get()) {
                    val t0 = System.nanoTime()
                    if (!nativeRunFrame()) {
                        val reason = nativeGetLastError()
                        reportOutcome("stopped", reason.ifEmpty { "The game stopped." }, hard = true)
                        runOnUiThread {
                            if (reason.isNotEmpty()) {
                                Toast.makeText(this, "The game stopped. $reason", Toast.LENGTH_LONG).show()
                                speak("The game stopped. $reason")
                            }
                            finishPlayerDelayed()
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
            } catch (e: Throwable) {
                Log.e(TAG, "Could not start $gameTitle", e)
                val reason = lastErrorOr(e.message ?: "Could not start $gameTitle.")
                // The reason also goes back to the page: the
                // player's own speech may be silent on the TV, and
                // the Toast carries the full text for the same reason.
                reportOutcome("failed", reason, hard = true)
                runOnUiThread {
                    Toast.makeText(this, "Could not start $gameTitle. $reason", Toast.LENGTH_LONG).show()
                    speak("Could not start $gameTitle. $reason")
                    finishPlayerDelayed()
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
        mainHandler.postDelayed(startWatchdog, START_WATCHDOG_MS)
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
        val base = "Hold the remote sideways, top to the left. " +
            "The D-pad is the control stick, channel up is A, channel down is B. " +
            "Tap OK for Start. Tap Back for Z. Hold Back and press the D-pad for the C buttons, " +
            "channel up for L, channel down for R. " +
            "Hold Back alone for half a second to leave."
        return if (packageManager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)) {
            base + " On this tablet there are also touch buttons on the screen: D-pad bottom left, A and B bottom right, C buttons above them."
        } else base
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
                // A bigger buffer absorbs short dips when the core
                // runs behind real time: the pitch still drops on a
                // sustained slowdown (only a faster core fixes that),
                // but brief stutters stop starving the track. The
                // 200 ms floor covers low sample rates.
                .setBufferSizeInBytes(Math.max(minBuf * 8, sampleRate * 4 / 5))
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
        for (code in remoteDpadHeld + touchDpadHeld) {
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

    /** Recomputes the touch C buttons and the right stick (analog
     * 1) from touchCHeld, mirroring refreshDpad's Back-held branch:
     * C-Up is JP_X, C-Down JP_A, C-Left JP_L, C-Right JP_R. The
     * right stick is shared with the remote's Back-held D-pad, so
     * the last writer wins there, as with the gamepad sticks. */
    private fun refreshTouchC() {
        for (id in touchCActive) nativeSetButton(id, false)
        touchCActive.clear()
        var cx = 0
        var cy = 0
        for (id in touchCHeld) {
            touchCActive.add(id)
            when (id) {
                JP_X -> cy = -STICK_MAX
                JP_A -> cy = STICK_MAX
                JP_L -> cx = -STICK_MAX
                JP_R -> cx = STICK_MAX
            }
        }
        for (id in touchCActive) nativeSetButton(id, true)
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

    // ---------- On-screen touch controls (tablet) ----------

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    private fun touchButtonBackground(pressed: Boolean): GradientDrawable {
        val d = GradientDrawable()
        d.cornerRadius = dp(12).toFloat()
        if (pressed) {
            d.setColor(Color.parseColor("#FFD700"))
            d.setStroke(dp(2), Color.BLACK)
        } else {
            d.setColor(Color.argb(140, 24, 24, 24))
            d.setStroke(dp(2), Color.parseColor("#FFD700"))
        }
        return d
    }

    private fun setTouchButtonVisual(b: Button, pressed: Boolean) {
        b.background = touchButtonBackground(pressed)
        b.setTextColor(if (pressed) Color.BLACK else Color.parseColor("#FFD700"))
    }

    /** A game button for the touch overlay. Direct touch holds the
     * button down (ACTION_DOWN presses, ACTION_UP / ACTION_CANCEL
     * releases, so holding works); a TalkBack double-tap is a
     * click, which cannot hold, so it does a short timed tap
     * instead. The touch listener consumes the gesture, so a
     * direct tap never also fires the click listener. */
    private fun touchButton(
        text: String,
        description: String,
        textSizeSp: Float = 20f,
        onPress: (Boolean) -> Unit
    ): Button {
        val b = Button(this)
        b.text = text
        b.textSize = textSizeSp
        b.contentDescription = description
        b.isFocusable = true
        b.isClickable = true
        setTouchButtonVisual(b, false)
        b.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    onPress(true)
                    setTouchButtonVisual(b, true)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    onPress(false)
                    setTouchButtonVisual(b, false)
                    true
                }
                else -> true
            }
        }
        b.setOnClickListener {
            onPress(true)
            setTouchButtonVisual(b, true)
            mainHandler.postDelayed({
                if (!closed.get()) onPress(false)
                setTouchButtonVisual(b, false)
            }, TOUCH_TAP_MS)
        }
        return b
    }

    /** Adds the overlay to the root, but only on a device with a
     * touchscreen — the Onn TV has none, so it stays clean there.
     * Buttons sit at the screen edges and never cover the middle
     * of the picture. Fixed positions, same every time, so the
     * layout can be learned by feel. No speech per press. */
    private fun addTouchOverlay(root: FrameLayout) {
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)) return

        // D-pad, bottom-left: drives the stick AND the D-pad
        // buttons through refreshDpad, exactly like the remote.
        // The tablet is held upright, so the top button feeds the
        // token for game Up (KEYCODE_DPAD_RIGHT in the sideways
        // remote map); the other three follow the same rotation.
        val dpad = RelativeLayout(this)
        dpad.layoutParams = FrameLayout.LayoutParams(dp(232), dp(232),
            Gravity.BOTTOM or Gravity.START).apply {
            leftMargin = dp(16)
            bottomMargin = dp(16)
        }
        val dpadUp = touchButton("▲", "D-pad up") { down ->
            if (down) touchDpadHeld.add(KeyEvent.KEYCODE_DPAD_RIGHT)
            else touchDpadHeld.remove(KeyEvent.KEYCODE_DPAD_RIGHT)
            refreshDpad()
        }
        dpadUp.layoutParams = RelativeLayout.LayoutParams(dp(72), dp(72)).apply {
            addRule(RelativeLayout.CENTER_HORIZONTAL)
            addRule(RelativeLayout.ALIGN_PARENT_TOP)
        }
        val dpadDown = touchButton("▼", "D-pad down") { down ->
            if (down) touchDpadHeld.add(KeyEvent.KEYCODE_DPAD_LEFT)
            else touchDpadHeld.remove(KeyEvent.KEYCODE_DPAD_LEFT)
            refreshDpad()
        }
        dpadDown.layoutParams = RelativeLayout.LayoutParams(dp(72), dp(72)).apply {
            addRule(RelativeLayout.CENTER_HORIZONTAL)
            addRule(RelativeLayout.ALIGN_PARENT_BOTTOM)
        }
        val dpadLeft = touchButton("◀", "D-pad left") { down ->
            if (down) touchDpadHeld.add(KeyEvent.KEYCODE_DPAD_UP)
            else touchDpadHeld.remove(KeyEvent.KEYCODE_DPAD_UP)
            refreshDpad()
        }
        dpadLeft.layoutParams = RelativeLayout.LayoutParams(dp(72), dp(72)).apply {
            addRule(RelativeLayout.CENTER_VERTICAL)
            addRule(RelativeLayout.ALIGN_PARENT_START)
        }
        val dpadRight = touchButton("▶", "D-pad right") { down ->
            if (down) touchDpadHeld.add(KeyEvent.KEYCODE_DPAD_DOWN)
            else touchDpadHeld.remove(KeyEvent.KEYCODE_DPAD_DOWN)
            refreshDpad()
        }
        dpadRight.layoutParams = RelativeLayout.LayoutParams(dp(72), dp(72)).apply {
            addRule(RelativeLayout.CENTER_VERTICAL)
            addRule(RelativeLayout.ALIGN_PARENT_END)
        }
        dpad.addView(dpadUp)
        dpad.addView(dpadDown)
        dpad.addView(dpadLeft)
        dpad.addView(dpadRight)
        root.addView(dpad)

        // C buttons, bottom-right above the A/B row: a small
        // cross. Each feeds its C button id and the right stick
        // (analog 1) through refreshTouchC, like the remote's
        // Back-held D-pad.
        val cpad = RelativeLayout(this)
        cpad.layoutParams = FrameLayout.LayoutParams(dp(176), dp(176),
            Gravity.BOTTOM or Gravity.END).apply {
            rightMargin = dp(16)
            bottomMargin = dp(104)
        }
        val cUp = touchButton("▲", "C up", 16f) { down ->
            if (down) touchCHeld.add(JP_X) else touchCHeld.remove(JP_X)
            refreshTouchC()
        }
        cUp.layoutParams = RelativeLayout.LayoutParams(dp(48), dp(48)).apply {
            addRule(RelativeLayout.CENTER_HORIZONTAL)
            addRule(RelativeLayout.ALIGN_PARENT_TOP)
        }
        val cDown = touchButton("▼", "C down", 16f) { down ->
            if (down) touchCHeld.add(JP_A) else touchCHeld.remove(JP_A)
            refreshTouchC()
        }
        cDown.layoutParams = RelativeLayout.LayoutParams(dp(48), dp(48)).apply {
            addRule(RelativeLayout.CENTER_HORIZONTAL)
            addRule(RelativeLayout.ALIGN_PARENT_BOTTOM)
        }
        val cLeft = touchButton("◀", "C left", 16f) { down ->
            if (down) touchCHeld.add(JP_L) else touchCHeld.remove(JP_L)
            refreshTouchC()
        }
        cLeft.layoutParams = RelativeLayout.LayoutParams(dp(48), dp(48)).apply {
            addRule(RelativeLayout.CENTER_VERTICAL)
            addRule(RelativeLayout.ALIGN_PARENT_START)
        }
        val cRight = touchButton("▶", "C right", 16f) { down ->
            if (down) touchCHeld.add(JP_R) else touchCHeld.remove(JP_R)
            refreshTouchC()
        }
        cRight.layoutParams = RelativeLayout.LayoutParams(dp(48), dp(48)).apply {
            addRule(RelativeLayout.CENTER_VERTICAL)
            addRule(RelativeLayout.ALIGN_PARENT_END)
        }
        cpad.addView(cUp)
        cpad.addView(cDown)
        cpad.addView(cLeft)
        cpad.addView(cRight)
        root.addView(cpad)

        // A / B, bottom-right: B left, A right (A is the big one).
        val abRow = LinearLayout(this)
        abRow.orientation = LinearLayout.HORIZONTAL
        abRow.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.END).apply {
            rightMargin = dp(16)
            bottomMargin = dp(16)
        }
        val bBtn = touchButton("B", "B button") { down -> nativeSetButton(JP_Y, down) }
        bBtn.layoutParams = LinearLayout.LayoutParams(dp(72), dp(72)).apply {
            rightMargin = dp(12)
        }
        val aBtn = touchButton("A", "A button") { down -> nativeSetButton(JP_B, down) }
        aBtn.layoutParams = LinearLayout.LayoutParams(dp(88), dp(72))
        abRow.addView(bBtn)
        abRow.addView(aBtn)
        root.addView(abRow)

        // Start, bottom-centre.
        val startBtn = touchButton("Start", "Start button", 16f) { down ->
            nativeSetButton(JP_START, down)
        }
        startBtn.layoutParams = FrameLayout.LayoutParams(dp(72), dp(48),
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = dp(16)
        }
        root.addView(startBtn)

        // Shoulders across the top: L left, Z centre, R right.
        val lBtn = touchButton("L", "L button", 16f) { down ->
            nativeSetButton(JP_SELECT, down)
        }
        lBtn.layoutParams = FrameLayout.LayoutParams(dp(96), dp(48),
            Gravity.TOP or Gravity.START).apply {
            leftMargin = dp(16)
            topMargin = dp(16)
        }
        root.addView(lBtn)
        val zBtn = touchButton("Z", "Z button", 16f) { down ->
            nativeSetButton(JP_L2, down)
        }
        zBtn.layoutParams = FrameLayout.LayoutParams(dp(96), dp(48),
            Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
            topMargin = dp(16)
        }
        root.addView(zBtn)
        val rBtn = touchButton("R", "R button", 16f) { down ->
            nativeSetButton(JP_R2, down)
        }
        rBtn.layoutParams = FrameLayout.LayoutParams(dp(96), dp(48),
            Gravity.TOP or Gravity.END).apply {
            rightMargin = dp(16)
            topMargin = dp(16)
        }
        root.addView(rBtn)
    }

    private fun finishPlayer() {
        if (closed.getAndSet(true)) return
        mainHandler.removeCallbacks(finishPlayerRunnable)
        // A deliberate exit (or a reported failure, whose outcome is
        // already recorded): clear the launch marker so MainActivity
        // does not announce this return as a silent crash.
        try { MainActivity.clearPlayerLaunchMarker() } catch (e: Exception) { }
        running.set(false)
        mainHandler.removeCallbacks(exitRunnable)
        mainHandler.removeCallbacks(startWatchdog)
        mainHandler.removeCallbacks(videoCheck)
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
