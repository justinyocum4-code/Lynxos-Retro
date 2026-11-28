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
import java.util.concurrent.atomic.AtomicLong

/**
 * Native Nintendo 64 player (milestone M1 of the native-player project).
 *
 * Runs a prebuilt N64 libretro core (v1.5.0: Mupen64Plus-Next
 * GLES3 first, Parallel N64 as fallback) as real ARM code through a
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
        const val EXTRA_CORE = "core" // "mupen" (first choice, v1.5.0) or "parallel" (fallback); any other value coerces to Mupen
        private const val TAG = "LynxosN64"
        private const val CORE_MUPEN_ASSET = "native/mupen64plus_next_gles3_libretro_android.so"
        private const val CORE_MUPEN_NAME = "mupen64plus_next_gles3_libretro_android.so"
        private const val CORE_PARALLEL_ASSET = "native/parallel_n64_libretro_android.so"
        private const val CORE_PARALLEL_NAME = "parallel_n64_libretro_android.so"
        // v1.5.0: bumped so a stale v1.4.12-era core file already in
        // native-cores is re-copied from assets on the next launch
        // of that core.
        private const val CORE_VERSION = "2"
        private const val BACK_HOLD_EXIT_MS = 500L
        private const val START_WATCHDOG_MS = 30_000L
        private const val VIDEO_CHECK_MS = 5_000L
        // Audio queue cap (v1.4.11): the speaker should never fall
        // behind the core (the core is the slow side), so 8 batches
        // is far more than enough; if it ever fills, the oldest
        // batch is dropped instead of growing latency without bound.
        private const val AUDIO_QUEUE_MAX_BATCHES = 8
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
    // Audio queue (v1.4.11): the core thread OFFERS batches here and
    // never blocks on the speaker; a dedicated audio thread drains
    // them. The old code wrote with WRITE_BLOCKING on the core
    // thread, so a full speaker buffer stalled the next frame and
    // turned a small speed deficit into constant static. The JNI
    // bridge allocates a fresh ShortArray per batch, so holding the
    // reference in the queue is safe — no array is ever reused.
    private val audioLock = Object()
    private val audioQueue = ArrayDeque<ShortArray>()
    private var audioThread: Thread? = null
    private val audioRunning = AtomicBoolean(false)
    // The sample rate the current track was built at. Written on the
    // UI thread (startAudio), read on the core thread (rate check).
    @Volatile
    private var currentAudioRate = 0
    // Audio diagnostics (v1.4.11): counted on the core/audio
    // threads, sampled once a second for the page's audio line.
    private val audioBatchesReceived = AtomicLong(0)
    private val audioFramesReceived = AtomicLong(0)
    private var audioUnderrunsTotal = 0L
    private var audioLastUnderrunCount = 0
    private var diagFramesRun = 0L
    private var diagWindowStartNanos = 0L
    private var audioRateCheckUntilNanos = 0L
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

    /** Called from the native bridge on the core thread with one audio batch.
     * Offers the batch into the queue and returns immediately — the
     * core thread must never block on the speaker (v1.4.11). */
    fun onAudioBatch(samples: ShortArray, frames: Int) {
        audioBatchesReceived.incrementAndGet()
        audioFramesReceived.addAndGet(frames.toLong())
        synchronized(audioLock) {
            while (audioQueue.size >= AUDIO_QUEUE_MAX_BATCHES) audioQueue.removeFirst()
            audioQueue.addLast(samples)
            audioLock.notifyAll()
        }
    }

    /** The audio drain thread (v1.4.11): takes batches off the queue
     * and writes them to the speaker. This is the only place a
     * blocking AudioTrack write happens, on a thread raised to audio
     * priority — never on the core thread. */
    private fun startAudioDrain() {
        // Capture the track: a later rate reopen builds a new one,
        // and this thread must keep writing only to the track it
        // started with (a write on a released track just errors).
        val track = audioTrack
        audioRunning.set(true)
        val t = Thread({
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO)
            while (audioRunning.get()) {
                val batch: ShortArray? = synchronized(audioLock) {
                    while (audioQueue.isEmpty() && audioRunning.get()) {
                        try {
                            audioLock.wait(100)
                        } catch (e: InterruptedException) {
                        }
                    }
                    if (audioQueue.isNotEmpty()) audioQueue.removeFirst() else null
                }
                if (batch != null) {
                    try {
                        track?.write(batch, 0, batch.size, AudioTrack.WRITE_BLOCKING)
                    } catch (e: Exception) {
                        Log.w(TAG, "audio write failed", e)
                    }
                }
            }
        }, "lynxos-n64-audio")
        audioThread = t
        t.start()
    }

    private fun stopAudioDrain() {
        audioRunning.set(false)
        val t = audioThread
        audioThread = null
        if (t != null) {
            t.interrupt()
            synchronized(audioLock) { audioLock.notifyAll() }
            try {
                t.join(500)
            } catch (e: Exception) {
            }
        }
        synchronized(audioLock) { audioQueue.clear() }
    }

    /** Forwards one audio-diagnostics line to the page (same
     * prefs-backed pattern as the Last-game line); MainActivity owns
     * the storage, this just forwards. */
    private fun reportAudioDiag(text: String) {
        try {
            MainActivity.reportN64AudioDiag(text)
        } catch (e: Exception) {
            Log.w(TAG, "report audio diag failed", e)
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

    /** The fitted game-surface size for a screen of sw x sh: the
     * picture shape (aspect) fitted inside the screen. Shared by
     * the creation-time size in onCreate and fitGameSurface() so
     * the two can never disagree. */
    private fun fittedSize(sw: Int, sh: Int, aspect: Float): Pair<Int, Int> {
        // Fit inside the screen: width-limited when the screen is
        // narrower than the picture shape, height-limited otherwise.
        return if (sw.toFloat() / sh.toFloat() > aspect) {
            (sh * aspect).toInt() to sh
        } else {
            sw to (sw / aspect).toInt()
        }
    }

    /** Size the game SurfaceView to the game's picture shape, fitted
     * inside the screen and centered (v1.4.10). The core draws its
     * own 4:3 frame bottom-anchored into whatever window it is
     * given, so a full-screen portrait window left the picture at
     * the bottom with black above it. A window already shaped to
     * the picture fills completely, and centering it puts the black
     * bars evenly around it instead of all on one side. The
     * SurfaceView is sized to the fit at creation (v1.4.14), so
     * the EGL surface is already the right size when the core's GL
     * init runs during load — no resize lands inside
     * retro_load_game or the deferred context_reset. This re-fits
     * it when the game loads (real aspect from AV geometry) and
     * on every surface size change. The touch overlay is positioned
     * against the full screen and is untouched by this — only the
     * SurfaceView moves. */
    private fun fitGameSurface() {
        if (!::rootLayout.isInitialized || !::surfaceView.isInitialized) return
        val sw = rootLayout.width
        val sh = rootLayout.height
        if (sw <= 0 || sh <= 0) return
        val aspect = if (gameAspect > 0) gameAspect else 4f / 3f
        val (w, h) = fittedSize(sw, sh, aspect)
        val lp = surfaceView.layoutParams as? FrameLayout.LayoutParams
        if (lp != null && lp.width == w && lp.height == h) return
        surfaceView.layoutParams = FrameLayout.LayoutParams(w, h, Gravity.CENTER)
        Log.i(TAG, "fit game surface ${w}x$h in ${sw}x$sh (aspect $aspect)")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        gameTitle = intent.getStringExtra(EXTRA_GAME_TITLE) ?: "game"
        val gamePath = intent.getStringExtra(EXTRA_GAME_PATH)
        // v1.5.0: Mupen first, Parallel fallback. MainActivity sends
        // "mupen" (first choice) or "parallel"; anything else
        // coerces to Mupen.
        val requestedCore = when (intent.getStringExtra(EXTRA_CORE)) {
            "parallel" -> "parallel"
            else -> "mupen"
        }
        // Next-launch fallback (v1.5.0): if the previous launch of
        // this game on Mupen died silently (native crash — the
        // process died, so no outcome was ever reported), start
        // with Parallel this time. The MainActivity launch marker
        // cannot be used here: it is overwritten with THIS launch
        // before the player starts, and the page clears the
        // Last-game line before launching — so the player keeps its
        // own attempt record (see recordCoreAttempt /
        // previousLaunchDiedSilently). A clean exit or a reported
        // outcome clears it; only a silent death leaves it behind.
        var coreChoice = requestedCore
        if (requestedCore == "mupen" && previousLaunchDiedSilently("mupen", gameTitle)) {
            Log.i(TAG, "previous Mupen launch of $gameTitle died silently; starting with Parallel")
            coreChoice = "parallel"
        }
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
        // Size the SurfaceView to the fitted 4:3 rect at creation
        // (v1.4.14): the core's GL init runs during load, inside
        // nativeLoadGame (retro_load_game + the deferred
        // context_reset), so the surface must already be the right
        // size when load starts. The old root.post fit call
        // resized it from full-screen just after creation, destroying
        // and recreating the surface — on the TV's slower timing
        // that destroy could land inside load and kill GL init.
        // Display metrics are the screen size here (fullscreen TV);
        // surfaceChanged re-fits against the measured root if they
        // ever differ, as before. gameAspect is still the 4:3
        // default at this point (the core reports the real one
        // only after load).
        val dm = resources.displayMetrics
        val startAspect = if (gameAspect > 0) gameAspect else 4f / 3f
        val (fitW, fitH) = fittedSize(dm.widthPixels, dm.heightPixels, startAspect)
        root.addView(surfaceView, FrameLayout.LayoutParams(fitW, fitH, Gravity.CENTER))
        setContentView(root)
        addTouchOverlay(root)

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
                // v1.5.0: Mupen first, Parallel fallback IN-PROCESS.
                // A core that fails cleanly (copy / load / options /
                // game load) is torn down (nativeClose + nativeInit —
                // nativeClose drops the JNI activity ref, so the
                // retry re-inits) and the other core is tried in the
                // same launch. A native crash kills the process, so
                // it cannot fall back here — the next launch reads
                // the attempt record instead (see onCreate).
                val attempts = if (coreChoice == "mupen") listOf("mupen", "parallel") else listOf("parallel")
                var loadedCore: String? = null
                var lastReason = ""
                for (core in attempts) {
                    if (tryLoadCore(core, gamePath)) {
                        loadedCore = core
                        break
                    }
                    lastReason = lastErrorOr("")
                    if (core != attempts.last()) {
                        val next = attempts[attempts.indexOf(core) + 1]
                        Log.i(TAG, coreDisplay(core) + " failed (" + lastReason + "); falling back to " + coreDisplay(next))
                        speakAndToast(coreDisplay(core) + " could not start this game. Trying " + coreDisplay(next) + ".")
                        try {
                            nativeClose()
                        } catch (e: Exception) {
                            Log.w(TAG, "close after failed core", e)
                        }
                        if (!nativeInit(this)) {
                            lastReason = lastErrorOr("The Nintendo 64 player could not start.")
                            break
                        }
                    }
                }
                val effectiveCore = loadedCore ?: throw IllegalStateException(
                    if (attempts.size > 1) {
                        "Mupen and Parallel could not start the game." + if (lastReason.isNotEmpty()) " $lastReason" else ""
                    } else {
                        lastErrorOr("The game could not be loaded.")
                    }
                )
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
                // Name the core aloud only when a fallback picked it
                // (in-process or next-launch): a plain Mupen launch
                // speaks exactly as before.
                val fellBack = effectiveCore != requestedCore
                speak("Now playing $gameTitle, Nintendo 64" + (if (fellBack) ", " + coreDisplay(effectiveCore) + " core" else "") + ". " + controlSummary())
                running.set(true)
                setLoadStage("running")
                mainHandler.removeCallbacks(startWatchdog)
                mainHandler.postDelayed(videoCheck, VIDEO_CHECK_MS)
                // Audio diagnostics window (v1.4.11): the frame
                // count in each 1-second window is the real
                // emulation speed; the rate check runs for the
                // first 5 seconds only.
                diagWindowStartNanos = System.nanoTime()
                audioRateCheckUntilNanos = diagWindowStartNanos + 5_000_000_000L
                val frameNanos = (1_000_000_000.0 / fps).toLong()
                // Per-phase timing (v1.4.13): total ns spent inside
                // nativeRunFrame() vs in app-side work around it,
                // per 1 s window. eglSwapBuffers lives inside
                // nativeRunFrame() in this bridge, so "rest" here is
                // app-side work only (diag, rate check, pacing
                // math) — the pacing sleep is excluded.
                var runFrameNanosTotal = 0L
                var restNanosTotal = 0L
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
                    val runEndNanos = System.nanoTime()
                    runFrameNanosTotal += runEndNanos - t0
                    // Diagnostics once a second (v1.4.11): actual
                    // frame rate, underrun count, average batch
                    // size — the numbers that prove whether the
                    // static is the core running slow. The line
                    // goes to the page (and logcat), never spoken.
                    diagFramesRun++
                    val nowNanos = System.nanoTime()
                    if (nowNanos - diagWindowStartNanos >= 1_000_000_000L) {
                        val elapsedSec = (nowNanos - diagWindowStartNanos) / 1_000_000_000.0
                        val actualFps = (diagFramesRun / elapsedSec).toInt()
                        // Underruns are cumulative since the track
                        // was built; keep a running total so a rate
                        // reopen (new track, counter reset) cannot
                        // make the number drop.
                        try {
                            val cur = audioTrack?.underrunCount ?: 0
                            if (cur > audioLastUnderrunCount) audioUnderrunsTotal += (cur - audioLastUnderrunCount).toLong()
                            audioLastUnderrunCount = cur
                        } catch (e: Exception) {
                        }
                        val batches = audioBatchesReceived.get()
                        val frames = audioFramesReceived.get()
                        val avgBatch = if (batches > 0) (frames / batches).toInt() else 0
                        val avgRunMs = if (diagFramesRun > 0) runFrameNanosTotal.toDouble() / diagFramesRun / 1_000_000.0 else 0.0
                        val avgRestMs = if (diagFramesRun > 0) restNanosTotal.toDouble() / diagFramesRun / 1_000_000.0 else 0.0
                        val diag = "$gameTitle audio: $actualFps frames a second, $audioUnderrunsTotal underruns, $avgBatch frames per batch, $currentAudioRate Hz, run ${String.format(Locale.US, "%.1f", avgRunMs)} ms, rest ${String.format(Locale.US, "%.1f", avgRestMs)} ms"
                        Log.i(TAG, "audio diag: $diag")
                        reportAudioDiag(diag)
                        diagFramesRun = 0
                        runFrameNanosTotal = 0
                        restNanosTotal = 0
                        diagWindowStartNanos = nowNanos
                        // Rate renegotiation (v1.4.11): the rate is
                        // read once at load, before the game has
                        // programmed its DAC rate. Re-read for the
                        // first 5 seconds and reopen the track ONLY
                        // when the change is real (>1%): Mario 64's
                        // 32040 -> 32000 is noise, not a new rate,
                        // but a game programming 44100 or 22050
                        // would otherwise play at the wrong pitch
                        // with chronic underrun.
                        if (nowNanos < audioRateCheckUntilNanos) {
                            try {
                                val rate = nativeGetSampleRate().toInt()
                                if (rate in 8000..192000 && currentAudioRate > 0 &&
                                    Math.abs(rate - currentAudioRate) > currentAudioRate / 100
                                ) {
                                    Log.i(TAG, "audio rate changed $currentAudioRate -> $rate, reopening track")
                                    runOnUiThread { startAudio(rate) }
                                }
                            } catch (e: Exception) {
                                Log.w(TAG, "audio rate re-read failed", e)
                            }
                        }
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
                    // Rest = app-side work after nativeRunFrame()
                    // returned, excluding the pacing sleep (any
                    // sleep overshoot lands here too).
                    restNanosTotal += System.nanoTime() - runEndNanos - maxOf(sleepNanos, 0L)
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

    /** Display name for a core id ("mupen" -> "Mupen"), used in
     * spoken / reported outcomes so a failure names the core tried. */
    private fun coreDisplay(core: String): String {
        return if (core == "mupen") "Mupen" else "Parallel"
    }

    // Next-launch fallback record (v1.5.0): "core|title|timeMillis"
    // in the same "lynxos-retro" prefs the player already uses for
    // its load-stage / bridge-step breadcrumbs. Written when a core
    // attempt starts, cleared on any clean end (finishPlayer — a
    // reported outcome always ends there). Only a silent death
    // (native crash: the process dies, finishPlayer never runs)
    // leaves it behind, so its presence means "the previous launch
    // of this game on this core died without saying anything". The
    // title sits between the first and last "|" so a "|" inside a
    // game title cannot break the parse.
    private fun recordCoreAttempt(core: String) {
        try {
            getSharedPreferences("lynxos-retro", MODE_PRIVATE).edit()
                .putString("n64LastCoreAttempt", "$core|$gameTitle|${System.currentTimeMillis()}").apply()
        } catch (e: Exception) {
        }
    }

    private fun clearCoreAttempt() {
        try {
            getSharedPreferences("lynxos-retro", MODE_PRIVATE).edit()
                .remove("n64LastCoreAttempt").apply()
        } catch (e: Exception) {
        }
    }

    private fun previousLaunchDiedSilently(core: String, title: String): Boolean {
        return try {
            val rec = getSharedPreferences("lynxos-retro", MODE_PRIVATE)
                .getString("n64LastCoreAttempt", null) ?: return false
            val firstPipe = rec.indexOf('|')
            val lastPipe = rec.lastIndexOf('|')
            firstPipe > 0 && lastPipe > firstPipe &&
                rec.substring(0, firstPipe) == core &&
                rec.substring(firstPipe + 1, lastPipe) == title
        } catch (e: Exception) {
            false
        }
    }

    /** One load attempt with one core: copy the .so, load it, set
     * its options, load the game. Returns false on any clean
     * failure (the reason is in nativeGetLastError); a native crash
     * kills the process instead of returning. Any Throwable from
     * the copy or the option calls is a clean failure too, so the
     * caller can fall back to the other core. */
    private fun tryLoadCore(core: String, gamePath: String): Boolean {
        return try {
            setLoadStage("copying the " + coreDisplay(core) + " game core")
            val coreFile = ensureCoreFile(core)
            recordCoreAttempt(core)
            val systemDir = File(filesDir, "native-system").apply { mkdirs() }.absolutePath
            val saveDir = File(filesDir, "native-saves").apply { mkdirs() }.absolutePath
            setLoadStage("loading the " + coreDisplay(core) + " game core")
            if (!nativeLoadCore(coreFile.absolutePath, systemDir, saveDir)) {
                Log.w(TAG, coreDisplay(core) + " core failed to load: " + lastErrorOr(""))
                return false
            }
            setLoadStage("setting " + coreDisplay(core) + " game options")
            applyCoreOptions(core)
            setLoadStage("loading the game")
            if (!nativeLoadGame(gamePath, gameTitle)) {
                Log.w(TAG, coreDisplay(core) + " game load failed: " + lastErrorOr(""))
                return false
            }
            true
        } catch (e: Throwable) {
            Log.w(TAG, coreDisplay(core) + " load attempt failed", e)
            false
        }
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
            // v1.5.0: every key and value verified against the
            // shipped mupen64plus_next_gles3 .so (strings-grep of
            // the downloaded buildbot file, sha256 41e3733b…) and
            // upstream libretro_core_options.h. NOTE:
            // ThreadedRenderer takes "True"/"False" — the core
            // parses it with strcmp(value, "True") — NOT
            // "enabled"/"disabled", which would silently parse as
            // off. There is no "gfxplugin-accuracy" key in Mupen
            // (that is a Parallel-only key); the fast-plugin lever
            // here is rdp-plugin gliden64.
            nativeSetOption("mupen64plus-43screensize", "320x240")
            nativeSetOption("mupen64plus-rdp-plugin", "gliden64")
            nativeSetOption("mupen64plus-rsp-plugin", "hle")
            nativeSetOption("mupen64plus-cpucore", "dynamic_recompiler")
            nativeSetOption("mupen64plus-ThreadedRenderer", "True")
            return
        }
        nativeSetOption("parallel-n64-gfxplugin", "glide64")
        nativeSetOption("parallel-n64-gfxplugin-accuracy", "low")
        nativeSetOption("parallel-n64-rspplugin", "hle")
        nativeSetOption("parallel-n64-cpucore", "dynamic_recompiler")
        // 320x240 (v1.4.13): the unset default is 640x480, 4x the
        // GL fragment work on this weak GPU — quartering it is the
        // best shot at closing the ~8% speed gap (Mario 64 at ~55
        // of ~60 fps).
        nativeSetOption("parallel-n64-43screensize", "320x240")
    }

    private fun startAudio(sampleRate: Int) {
        // The start is posted to the UI thread; if the player
        // already finished (load failed, early exit), there is no
        // track to build — a late start must not resurrect one.
        if (closed.get()) return
        try {
            // A restart (rate renegotiation) shuts the old drain
            // down and releases the old track first: old-rate
            // samples must never play at the new rate.
            stopAudioDrain()
            try {
                audioTrack?.stop()
            } catch (e: Exception) {
            }
            try {
                audioTrack?.release()
            } catch (e: Exception) {
            }
            audioTrack = null
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
            currentAudioRate = sampleRate
            // Pre-fill ~100 ms of silence before play(): the first
            // real batches arrive while the track spins up, and
            // without this the track underruns on the very first
            // sound. The write returns at once (the buffer is
            // empty), so this does not stall the UI thread.
            val silence = ShortArray(sampleRate / 10 * 2)
            audioTrack?.write(silence, 0, silence.size, AudioTrack.WRITE_BLOCKING)
            audioTrack?.play()
            startAudioDrain()
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
        // v1.5.0: this was a clean end, not a silent death — clear
        // the core-attempt record so the next launch retries the
        // first-choice core. A native crash never reaches here, so
        // its record survives for the next-launch fallback.
        clearCoreAttempt()
        running.set(false)
        mainHandler.removeCallbacks(exitRunnable)
        mainHandler.removeCallbacks(startWatchdog)
        mainHandler.removeCallbacks(videoCheck)
        try {
            coreThread?.join(1000)
        } catch (e: Exception) {
        }
        // Stop the audio drain first (v1.4.11): it is the only other
        // thread touching the track, and it must be gone before the
        // track is released.
        stopAudioDrain()
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
