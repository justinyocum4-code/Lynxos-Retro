package com.lynxosretro.app

import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
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
import dev.kotlinds.libretrokmp.Device
import dev.kotlinds.libretrokmp.JoypadButton
import dev.kotlinds.libretrokmp.LibretroCore
import dev.kotlinds.libretrokmp.LibretroFrontend
import dev.kotlinds.libretrokmp.LogLevel
import dev.kotlinds.libretrokmp.MemoryType
import dev.kotlinds.libretrokmp.VideoFrame
import java.io.File
import java.io.FileOutputStream
import java.util.Collections
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Native player (v1.6.0: PlayStation plus the 2D systems — NES,
 * SNES, Game Boy, Game Boy Color, Game Boy Advance, Genesis,
 * Master System, Game Gear, Atari 2600).
 *
 * Runs a prebuilt libretro core as real ARM code through
 * libretro-kmp (bundled JNI bridge, no NDK build on our side). The WebView
 * UI stays in charge of the library and cloud sync; this Activity only
 * plays one game file, then returns to the library. Nintendo DS stays
 * on the web player (melonDS is too heavy for this player yet).
 *
 * Controls mirror the WebView player exactly: the remote is held sideways,
 * channel-up is A, channel-down is B, OK is Start, Back tap is
 * X, Back-held chords give Y / L / R, mute is Select, and holding Back alone
 * for half a second leaves the game. A standard gamepad (Switch controller)
 * works with its own buttons, no rotation. On a touchscreen device (the
 * tablet) there is also an on-screen touch controller: D-pad bottom-left,
 * action diamond bottom-right, Start/Select bottom-centre, L1/R1 top
 * corners. It is hidden on the TV, which has no touchscreen.
 */
class NativePlayerActivity : Activity(), SurfaceHolder.Callback {

    companion object {
        const val EXTRA_GAME_PATH = "gamePath"
        const val EXTRA_GAME_TITLE = "gameTitle"
        const val EXTRA_GAME_SYSTEM = "system"
        private const val TAG = "NativePlayer"
        // v1.6.0: one core per system. The 2D cores are the Nov 27
        // 2026 nightly arm64-v8a builds from the libretro buildbot
        // (all verified 64-bit ELF before shipping); PS1 keeps its
        // core here for the (currently menu-hidden) psx path.
        private const val CORE_VERSION = "2"
        private fun coreForSystem(system: String): Pair<String, String>? = when (system) {
            "nes" -> "native/fceumm_libretro_android.so" to "fceumm_libretro_android.so"
            "snes" -> "native/snes9x_libretro_android.so" to "snes9x_libretro_android.so"
            "gb", "gbc" -> "native/gambatte_libretro_android.so" to "gambatte_libretro_android.so"
            "gba" -> "native/mgba_libretro_android.so" to "mgba_libretro_android.so"
            "segaMD", "segaMS", "segaGG" -> "native/genesis_plus_gx_libretro_android.so" to "genesis_plus_gx_libretro_android.so"
            "atari2600" -> "native/stella2014_libretro_android.so" to "stella2014_libretro_android.so"
            "psx" -> "native/pcsx_rearmed_libretro_android.so" to "pcsx_rearmed_libretro_android.so"
            else -> null
        }
        private const val BACK_HOLD_EXIT_MS = 500L
        private const val WATCHDOG_CHECK_MS = 30_000L
        private const val WATCHDOG_MAX_IDLE_CHECKS = 4
        private const val VIDEO_CHECK_MS = 5_000L
        private const val TRIANGLE_TAP_MS = 100L
        // Audio queue cap (v1.4.12): batches wait here only briefly
        // — the drain thread runs just behind the core (the core is
        // the slow side), so 8 batches is far more than enough; if
        // it ever fills, the oldest batch is dropped instead of
        // growing latency without bound.
        private const val AUDIO_QUEUE_MAX_BATCHES = 8
        // A TalkBack double-tap (click) cannot hold a button, so it
        // does a short timed tap instead of a hold.
        private const val TOUCH_TAP_MS = 150L
    }

    private lateinit var surfaceView: SurfaceView
    private var core: LibretroCore? = null
    private var audioTrack: AudioTrack? = null
    // Audio queue (v1.4.12): the emulation thread OFFERS batches
    // here and never blocks on the speaker; a dedicated audio
    // thread drains them. The old code wrote with WRITE_BLOCKING
    // on the emulation thread (inside libretro-kmp's run()), so a
    // full speaker buffer stalled the next frame and turned a
    // small speed deficit into constant static — the same shape
    // v1.4.11 fixed on N64. libretro-kmp may reuse the ShortArray
    // it hands to onAudio, so each batch is COPIED into a fresh
    // array before queueing; the queue only ever holds arrays
    // this player owns.
    private val audioLock = Object()
    private val audioQueue = ArrayDeque<ShortArray>()
    private var audioThread: Thread? = null
    private val audioRunning = AtomicBoolean(false)
    // The sample rate the current track was built at. Written on
    // the UI thread (startAudio).
    @Volatile
    private var currentAudioRate = 0
    // Audio diagnostics (v1.4.12): counted on the emulation/audio
    // threads, sampled once a second for the page's audio line.
    private val audioBatchesReceived = AtomicLong(0)
    private val audioFramesReceived = AtomicLong(0)
    private var audioUnderrunsTotal = 0L
    private var audioLastUnderrunCount = 0
    private var diagFramesRun = 0L
    private var diagWindowStartNanos = 0L
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
    // v1.6.0: which system this launch is for — picks the core, the
    // core options, the spoken labels and the save file. Set in
    // onCreate from the intent's "system" extra (the bridge always
    // sends it); "psx" is the fallback for an old launch with no
    // extra, which is also the old player's only behaviour.
    private var gameSystem = "psx"

    /** Plain-language system name for spoken lines. */
    private fun systemLabel(): String = when (gameSystem) {
        "nes" -> "Nintendo"
        "snes" -> "Super Nintendo"
        "gb" -> "Game Boy"
        "gbc" -> "Game Boy Color"
        "gba" -> "Game Boy Advance"
        "segaMD" -> "Genesis"
        "segaMS" -> "Master System"
        "segaGG" -> "Game Gear"
        "atari2600" -> "Atari 2600"
        "psx" -> "PlayStation"
        else -> gameSystem
    }
    // Read on the main thread by the watchdog, written on the load
    // thread by setLoadStage: volatile so a stage advance is visible
    // to the watchdog without delay.
    @Volatile
    private var loadStage = "starting the player"

    // Load-stage breadcrumb that survives a process death: the
    // in-memory stage dies with the process, so each stage is also
    // written to SharedPreferences. If the player dies silently,
    // MainActivity reads it back and the "closed" announcement can
    // name the stage it died in.
    private fun setLoadStage(stage: String) {
        loadStage = stage
        Log.i(TAG, "load stage: $stage")
        try {
            getSharedPreferences("lynxos-retro", MODE_PRIVATE).edit()
                .putString("playerLoadStage", stage).apply()
        } catch (e: Exception) {
        }
    }

    private var tts: TextToSpeech? = null
    private var pendingSpeak: String? = null

    private val paint = Paint().apply { isFilterBitmap = false }

    // Video thread (v1.4.13): the canvas draw (lockCanvas /
    // drawColor / drawBitmap / unlockCanvasAndPost on a full-screen
    // SurfaceView, ~1920x1200) used to ride on the emulation thread
    // inside onVideoFrame, plausibly 5-15 ms of the 25 ms frame.
    // It now runs on a dedicated HandlerThread, "lynxos-native-video",
    // chosen over the UI thread so a slow draw can never stall UI
    // input/Toast/TTS and the thread's lifecycle is owned here
    // (start in startLoop, stop in finishPlayer) instead of
    // depending on the main looper. The emulation thread only does
    // the cheap setPixels (~0.1-0.3 ms for 320x240) and posts the
    // draw; if the draw lags a frame, video drops a frame while
    // audio stays clean, which frameskip already accepts.
    // Ping-pong bitmaps: the emulation thread writes ONLY the back
    // bitmap, the video thread draws ONLY the front one, and the
    // swap happens under videoLock only when no draw is pending or
    // in flight — so the emulation thread can never overwrite a
    // bitmap mid-draw. A frame that arrives while a draw is busy
    // is dropped (its pixels stay in the back bitmap and are
    // presented by the next swap, so the newest frame still wins).
    private val videoLock = Object()
    private var videoBackBitmap: Bitmap? = null
    private var videoFrontBitmap: Bitmap? = null
    private var videoFrontReady = false
    private var videoDrawPending = false
    private var videoDrawing = false
    private var videoBackDirty = false
    private var videoThread: HandlerThread? = null
    private var videoHandler: Handler? = null
    private val videoRunning = AtomicBoolean(false)
    // Per-phase timing (v1.4.13): accumulated per 1 s diag window
    // and reset by the loop thread when it reports. runNanos is
    // the whole c.run() call (it includes the onVideoFrame
    // callback, which runs synchronously inside run()); the
    // callback nanos are tracked separately so the core-only
    // average is run minus callback, and the video average is
    // callback plus the draw-thread time.
    private val diagRunNanos = AtomicLong(0)
    private val diagVideoCallbackNanos = AtomicLong(0)
    private val diagDrawNanos = AtomicLong(0)
    private val diagPresentedWindow = AtomicInteger(0)

    // Video-state diagnostics: frames the core delivered vs frames
    // actually posted to the surface. If the game runs (sound) but
    // nothing is drawn, these two counts name the layer without
    // logcat — the 5 s check below speaks the difference.
    private val framesPresented = AtomicInteger(0)
    private val framesDrawn = AtomicInteger(0)
    private val surfaceReady = AtomicBoolean(false)
    private var videoCheckDone = false

    private val exitRunnable = Runnable {
        if (backHeld && !backChordUsed) finishPlayer()
    }

    // Start watchdog (v1.4.14), same shape as the N64 player but
    // progress-aware: the TV is much slower than the tablet, so a
    // load that is still working must NOT be killed at 30 s.
    // Instead of one fixed wall-clock kill, this checks every
    // 30 s whether the load has visibly progressed — the load
    // thread advances loadStage ("starting the player" ->
    // "copying the game core" -> "loading the game" -> "running")
    // as it works, and any advance restarts the idle clock. A
    // check with no advance announces "still loading" (Toast +
    // TTS, the TV's own speech may be silent) and counts one idle
    // check; four idle checks in a row — 120 s with no observable
    // progress at all — still means a genuinely stuck load, so the
    // old warning + 5 s delayed finish fires. The finish is
    // delayed 5 s after the warning and guarded on running still
    // being false: finishPlayer stops TTS, so finishing
    // immediately would cut the warning off unheard.
    private var watchdogIdleChecks = 0
    private var watchdogLastStage = "starting the player"

    private val startWatchdog = object : Runnable {
        override fun run() {
        if (running.get() || closed.get()) return
        if (loadStage != watchdogLastStage) {
            // The load advanced a stage since the last check: it is
            // still working, so the idle clock restarts.
            watchdogLastStage = loadStage
            watchdogIdleChecks = 0
            Log.i(TAG, "watchdog: load progressing, stage=$loadStage")
        } else {
            watchdogIdleChecks++
            if (watchdogIdleChecks >= WATCHDOG_MAX_IDLE_CHECKS) {
                speakAndToast("The ${systemLabel()} game is taking too long to start.")
                mainHandler.postDelayed({
                    if (!running.get() && !closed.get()) {
                        reportOutcome("hang", "The game did not start after two and a half minutes.", hard = true)
                        finishPlayer()
                    }
                }, 5000L)
                return
            }
            speakAndToast("Still loading $gameTitle. Please wait.")
        }
        mainHandler.postDelayed(this, WATCHDOG_CHECK_MS)
        }
    }

    // Video check, 5 s after the frame loop starts: the game is
    // running (sound proves the core is alive), so a zero drawn
    // count here means sound-with-no-picture. The two counts name
    // the layer: no frames delivered at all means the core is not
    // producing video; frames delivered but none drawn means the
    // screen is not ready. Fires once; a game that starts drawing
    // late is left alone.
    private val videoCheck = Runnable {
        if (!videoCheckDone) {
            videoCheckDone = true
            val presented = framesPresented.get()
            val drawn = framesDrawn.get()
            if (drawn == 0) {
                val reason = if (presented == 0)
                    "No video frames from the game."
                else
                    "The game screen is not ready."
                speakAndToast("The game is running but no picture has been drawn yet. $reason")
                reportOutcome("novideo", reason, hard = false)
                Log.w(TAG, "video check: presented=$presented drawn=$drawn surfaceReady=${surfaceReady.get()}")
            } else {
                Log.i(TAG, "video check: presented=$presented drawn=$drawn surfaceReady=${surfaceReady.get()}")
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
        try { MainActivity.reportPlayerOutcome(gameSystem, kind, reason, hard) } catch (e: Exception) {
            Log.w(TAG, "report outcome failed", e)
        }
    }

    // ----- Surface lifecycle -----

    override fun surfaceCreated(holder: SurfaceHolder) {
        surfaceReady.set(true)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceReady.set(false)
    }

    private val frontend = object : LibretroFrontend {
        override val systemDirectory: String
            get() = File(filesDir, "native-system").apply { mkdirs() }.absolutePath
        override val saveDirectory: String
            get() = File(filesDir, "native-saves").apply { mkdirs() }.absolutePath

        // Core options, per system. The 2D cores run at full speed
        // on their defaults — nothing to tune, so they get no
        // options at all, except one: Genesis Plus GX picks the
        // emulated hardware from the game file's extension, and the
        // export cache renames single files to ".bin", which GX
        // would read as a Mega Drive game. Forcing system_hw here
        // makes the extension irrelevant (key and values verified
        // against the shipped .so: "master system", "game gear",
        // "mega drive / genesis" all present).
        // PS1 speed options, same values already tuned in the browser
        // player (verified in the bundled core): frameskip on auto with
        // a 60% threshold, and the instruction-cache emulation off.
        // v1.4.13: dithering off, SPU interpolation off, the dynamic
        // recompiler explicitly on, and both NEON enhancement options
        // explicitly off — small cumulative savings on top of the
        // video-thread move. Everything else keeps the core's default.
        override fun variable(key: String): String? {
            if (key == "genesis_plus_gx_system_hw") {
                return when (gameSystem) {
                    "segaMS" -> "master system"
                    "segaGG" -> "game gear"
                    "segaMD" -> "mega drive / genesis"
                    else -> null
                }
            }
            if (gameSystem != "psx") return null
            return when (key) {
                "pcsx_rearmed_frameskip" -> "auto_threshold"
                "pcsx_rearmed_frameskip_threshold" -> "60"
                "pcsx_rearmed_frameskip_interval" -> "3"
                "pcsx_rearmed_icache_emulation" -> "disabled"
                "pcsx_rearmed_dithering" -> "disabled"
                "pcsx_rearmed_spu_interpolation" -> "off"
                "pcsx_rearmed_drc" -> "enabled"
                "pcsx_rearmed_neon_enhancement_enable" -> "disabled"
                "pcsx_rearmed_neon_enhancement_texture" -> "disabled"
                else -> null
            }
        }

        override fun onVideoFrame(frame: VideoFrame) {
            // v1.4.13: only the cheap setPixels stays on the
            // emulation thread; the canvas draw is posted to the
            // video thread (see videoDrawRunnable). Never block
            // here waiting for a draw.
            val cbStart = System.nanoTime()
            framesPresented.incrementAndGet()
            diagPresentedWindow.incrementAndGet()
            var back = videoBackBitmap
            if (back == null || back.width != frame.width || back.height != frame.height) {
                back = Bitmap.createBitmap(frame.width, frame.height, Bitmap.Config.ARGB_8888)
                videoBackBitmap = back
            }
            back.setPixels(frame.pixels, 0, frame.width, 0, 0, frame.width, frame.height)
            val postDraw = synchronized(videoLock) {
                videoBackDirty = true
                // Swap only when no draw is pending or in flight:
                // the video thread owns the front bitmap until its
                // draw finishes, and the emulation thread must never
                // overwrite it. A frame arriving mid-draw is dropped
                // as a presented frame, but its pixels stay in the
                // back bitmap and the draw runnable re-posts after
                // finishing, so the newest frame still gets shown.
                if (!videoDrawPending && !videoDrawing) {
                    val oldFront = videoFrontBitmap
                    videoFrontBitmap = back
                    videoBackBitmap = oldFront
                    videoFrontReady = true
                    videoDrawPending = true
                    videoBackDirty = false
                    true
                } else {
                    false
                }
            }
            if (postDraw) videoHandler?.post(videoDrawRunnable)
            diagVideoCallbackNanos.addAndGet(System.nanoTime() - cbStart)
        }

        override fun onAudio(samples: ShortArray, frames: Int) {
            // Offer a copy into the queue and return at once — the
            // emulation thread must never block on the speaker
            // (v1.4.12). The copy is defensive: libretro-kmp may
            // reuse the array it passes, and the queue only holds
            // arrays this player owns. Copy exactly frames*2
            // elements, the length the old write used.
            try {
                audioBatchesReceived.incrementAndGet()
                audioFramesReceived.addAndGet(frames.toLong())
                val copy = samples.copyOf(frames * 2)
                synchronized(audioLock) {
                    while (audioQueue.size >= AUDIO_QUEUE_MAX_BATCHES) audioQueue.removeFirst()
                    audioQueue.addLast(copy)
                    audioLock.notifyAll()
                }
            } catch (e: Exception) {
                Log.w(TAG, "audio offer failed", e)
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

    /** The audio drain thread (v1.4.12): takes batches off the queue
     * and writes them to the speaker. This is the only place a
     * blocking AudioTrack write happens, on a thread raised to audio
     * priority — never on the emulation thread. */
    private fun startAudioDrain() {
        // Capture the track: a later restart builds a new one, and
        // this thread must keep writing only to the track it started
        // with (a write on a released track just errors).
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
        }, "lynxos-native-audio")
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

    /** The video draw runnable (v1.4.13): runs ONLY on the video
     * thread, drawing the front (ping-pong) bitmap. Never called
     * from the emulation thread. If a newer frame landed in the
     * back bitmap while this draw was running, it re-posts itself
     * so the newest frame still gets shown. */
    private val videoDrawRunnable: Runnable = object : Runnable {
        override fun run() {
        if (!videoRunning.get()) return
        val bmp: Bitmap? = synchronized(videoLock) {
            if (!videoFrontReady) {
                videoDrawPending = false
                null
            } else {
                videoDrawing = true
                videoFrontBitmap
            }
        }
        if (bmp != null) {
            val drawStart = System.nanoTime()
            drawFrame(bmp)
            diagDrawNanos.addAndGet(System.nanoTime() - drawStart)
            val repost = synchronized(videoLock) {
                videoDrawing = false
                videoDrawPending = false
                // A frame arrived mid-draw: its pixels are in the
                // back bitmap, so swap it in and draw again rather
                // than leaving a stale frame on screen.
                if (videoBackDirty) {
                    val back = videoBackBitmap
                    if (back != null) {
                        val oldFront = videoFrontBitmap
                        videoFrontBitmap = back
                        videoBackBitmap = oldFront
                        videoFrontReady = true
                        videoDrawPending = true
                        videoBackDirty = false
                        true
                    } else false
                } else false
            }
            if (repost) videoHandler?.post(this)
        }
        }
    }

    /** Starts the dedicated video thread (v1.4.13). Called from
     * startLoop, before the emulation thread starts producing
     * frames; a no-op if it is already running. */
    private fun startVideoThread() {
        if (videoRunning.get()) return
        videoRunning.set(true)
        val t = HandlerThread("lynxos-native-video")
        t.start()
        videoThread = t
        videoHandler = Handler(t.looper)
    }

    /** Stops the video thread cleanly (v1.4.13): clears any pending
     * draw first so nothing draws after the surface is destroyed,
     * then quits and joins. Called from finishPlayer after the
     * emulation thread has been joined, before the core is closed
     * — the same stop-the-drain-first care as stopAudioDrain. */
    private fun stopVideoThread() {
        videoRunning.set(false)
        synchronized(videoLock) {
            videoFrontReady = false
            videoDrawPending = false
            videoBackDirty = false
        }
        val handler = videoHandler
        videoHandler = null
        handler?.removeCallbacks(videoDrawRunnable)
        val t = videoThread
        videoThread = null
        if (t != null) {
            try {
                t.quitSafely()
            } catch (e: Exception) {
            }
            try {
                t.join(500)
            } catch (e: Exception) {
            }
        }
    }

    /** Forwards one audio-diagnostics line to the page (same
     * prefs-backed pattern as the Last-game line); MainActivity owns
     * the storage, this just forwards. */
    private fun reportAudioDiag(text: String) {
        try {
            MainActivity.reportPS1AudioDiag(text)
        } catch (e: Exception) {
            Log.w(TAG, "report audio diag failed", e)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        gameTitle = intent.getStringExtra(EXTRA_GAME_TITLE) ?: "game"
        gameSystem = intent.getStringExtra(EXTRA_GAME_SYSTEM) ?: "psx"
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
        // NO background on the SurfaceView itself. An opaque
        // background clears SKIP_DRAW, so the view no longer punches
        // its hole in the window (SurfaceView.gatherTransparentRegion
        // removes the rect from the transparent region instead of
        // adding it) and the video surface below stays hidden behind
        // opaque black: sound, no picture. The root's black is fine
        // — only the SurfaceView's own background kills the hole.
        surfaceView.holder.addCallback(this)
        root.addView(surfaceView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
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

        Thread {
            try {
                setLoadStage("starting the player")
                setLoadStage("copying the game core")
                val (coreAsset, coreName) = coreForSystem(gameSystem)
                    ?: throw IllegalStateException("No native core for $gameSystem.")
                val coreFile = ensureCoreFile(coreAsset, coreName)
                val c = LibretroCore(coreFile.absolutePath, frontend)
                core = c
                setLoadStage("loading the game")
                c.loadGame(gamePath)
                c.setControllerPortDevice(0, Device.JOYPAD)
                // In-game saves: restore this game's battery save
                // (if it has one) before the first frame, the same
                // <title>.srm pattern the N64 bridge uses.
                loadSaveRam(c)
                val fps = c.avInfo.timing.fps
                val sampleRate = c.avInfo.timing.sampleRate.toInt()
                runOnUiThread { startAudio(sampleRate) }
                speak("Now playing $gameTitle, ${systemLabel()}. " + controlSummary())
                startLoop(fps)
            } catch (e: Throwable) {
                Log.e(TAG, "Could not start $gameTitle", e)
                // Speak the reason, like the N64 player does: a bare
                // "Could not start" leaves Justin guessing. libretro-kmp
                // throws with the load failure as its message. The
                // reason also goes back to the page (reportOutcome),
                // because the player's own speech may be silent on
                // the TV — and the Toast carries the full text, not
                // the short version, for the same reason.
                val reason = e.message?.takeIf { it.isNotBlank() } ?: "The game could not be started."
                reportOutcome("failed", reason, hard = true)
                runOnUiThread {
                    Toast.makeText(this, "Could not start $gameTitle. $reason", Toast.LENGTH_LONG).show()
                    speak("Could not start $gameTitle. $reason")
                    finishPlayerDelayed()
                }
            }
        }.start()
        mainHandler.postDelayed(startWatchdog, WATCHDOG_CHECK_MS)
    }

    private fun controlSummary(): String {
        // Describes THIS player's map: remote held sideways,
        // channel-up A, channel-down B, OK Start, Back tap X,
        // Back+OK Y, Back+channel-up L, Back+channel-down R, mute
        // Select, Back held alone half a second to leave. The
        // two-button systems (Game Boy, Atari) only need A/B/Start,
        // so they get the short version; SNES/GBA/Genesis use the
        // shoulders too. Genesis: RetroPad X is the C button.
        val base = "Hold the remote sideways, top to the left. " +
            "Channel up is A, channel down is B. Tap OK for Start. "
        val leave = "Hold Back alone for half a second to leave."
        return when (gameSystem) {
            "psx" -> base + "Tap Back is Triangle. Hold Back and press OK for Square, " +
                "channel up for L1, channel down for R1. " + leave
            "snes", "gba" -> base + "Tap Back is X. Hold Back and press OK for Y, " +
                "channel up for L, channel down for R. " + leave
            "segaMD", "segaMS", "segaGG" -> base + "Tap Back is C. " + leave
            else -> base + leave
        } + if (packageManager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)) {
            " On this tablet there are also touch buttons on the screen: D-pad bottom left, action buttons bottom right."
        } else ""
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

    /** Copies the bundled core .so from assets to internal storage (dlopen needs a real file).
     * v1.6.0: one version file per core ("<name>.version") — the old
     * shared version.txt was written by both native players with
     * different versions, so each switch re-copied the core. */
    private fun ensureCoreFile(coreAsset: String, coreName: String): File {
        val dir = File(filesDir, "native-cores")
        dir.mkdirs()
        val out = File(dir, coreName)
        val ver = File(dir, "$coreName.version")
        val current = try { ver.readText() } catch (e: Exception) { "" }
        if (!out.exists() || current != CORE_VERSION) {
            assets.open(coreAsset).use { input ->
                FileOutputStream(out).use { output -> input.copyTo(output) }
            }
            ver.writeText(CORE_VERSION)
        }
        return out
    }

    /** In-game (battery) saves, the N64 bridge's <title>.srm
     * pattern: the file is named after the sanitised game title and
     * lives in filesDir/native-saves next to the PS1 saves. Loaded
     * right after loadGame (before the first frame), saved when the
     * player closes. A core with no SAVE_RAM region (Atari 2600 has
     * no battery saves) simply has nothing to load or store. */
    private fun saveRamFile(): File {
        val safe = gameTitle.map {
            if (it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '.' || it == '_' || it == '-') it else '_'
        }.joinToString("").ifEmpty { "game" }
        return File(File(filesDir, "native-saves").apply { mkdirs() }, "$safe.srm")
    }

    private fun loadSaveRam(c: LibretroCore) {
        try {
            if (c.memorySize(MemoryType.SAVE_RAM) <= 0) return
            val f = saveRamFile()
            if (!f.exists()) return
            val bytes = f.readBytes()
            if (bytes.isNotEmpty() && c.writeMemory(MemoryType.SAVE_RAM, bytes)) {
                Log.i(TAG, "loaded ${bytes.size} save bytes from ${f.name}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "load save failed", e)
        }
    }

    private fun saveSaveRam(c: LibretroCore) {
        try {
            if (c.memorySize(MemoryType.SAVE_RAM) <= 0) return
            val bytes = c.readMemory(MemoryType.SAVE_RAM) ?: return
            if (bytes.isEmpty()) return
            val f = saveRamFile()
            f.writeBytes(bytes)
            Log.i(TAG, "saved ${bytes.size} save bytes to ${f.name}")
        } catch (e: Exception) {
            Log.w(TAG, "save save failed", e)
        }
    }

    private fun startAudio(sampleRate: Int) {
        // The start is posted to the UI thread; if the player
        // already finished (load failed, early exit), there is no
        // track to build — a late start must not resurrect one.
        if (closed.get()) return
        try {
            // A restart shuts the old drain down and releases the
            // old track first: old-rate samples must never play at
            // the new rate.
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

    private fun startLoop(fps: Double) {
        running.set(true)
        setLoadStage("running")
        mainHandler.removeCallbacks(startWatchdog)
        mainHandler.postDelayed(videoCheck, VIDEO_CHECK_MS)
        // Video thread first (v1.4.13): it must exist before the
        // emulation thread starts producing frames to post.
        startVideoThread()
        // Audio diagnostics window (v1.4.12): the frame count in
        // each 1-second window is the real emulation speed.
        diagWindowStartNanos = System.nanoTime()
        val frameNanos = (1_000_000_000.0 / fps).toLong()
        loopThread = Thread {
            val c = core ?: return@Thread
            while (running.get()) {
                val t0 = System.nanoTime()
                try {
                    c.run()
                } catch (e: Throwable) {
                    Log.e(TAG, "core run failed", e)
                    val reason = e.message?.takeIf { it.isNotBlank() } ?: "The game stopped."
                    reportOutcome("stopped", reason, hard = true)
                    runOnUiThread {
                        Toast.makeText(this, "The game stopped. $reason", Toast.LENGTH_LONG).show()
                        speak("The game stopped. $reason")
                        finishPlayerDelayed()
                    }
                    return@Thread
                }
                // Diagnostics once a second (v1.4.12): actual frame
                // rate, underrun count, average batch size — the
                // numbers that prove whether the static is the core
                // running slow. The line goes to the page (and
                // logcat), never spoken. The sample rate is fixed
                // per core (read from avInfo at load), so unlike
                // N64 there is no rate renegotiation to do here.
                diagFramesRun++
                val nowNanos = System.nanoTime()
                if (nowNanos - diagWindowStartNanos >= 1_000_000_000L) {
                    val elapsedSec = (nowNanos - diagWindowStartNanos) / 1_000_000_000.0
                    val actualFps = (diagFramesRun / elapsedSec).toInt()
                    // Underruns are cumulative since the track was
                    // built; keep a running total so a restart (new
                    // track, counter reset) cannot make the number
                    // drop.
                    try {
                        val cur = audioTrack?.underrunCount ?: 0
                        if (cur > audioLastUnderrunCount) audioUnderrunsTotal += (cur - audioLastUnderrunCount).toLong()
                        audioLastUnderrunCount = cur
                    } catch (e: Exception) {
                    }
                    val batches = audioBatchesReceived.get()
                    val frames = audioFramesReceived.get()
                    val avgBatch = if (batches > 0) (frames / batches).toInt() else 0
                    // Per-phase timing (v1.4.13): core-only average
                    // is the whole run() call minus the video
                    // callback that runs synchronously inside it;
                    // the video average is the callback plus the
                    // draw-thread time, per presented frame. The
                    // presented fps is the rate frames actually
                    // reached the video thread, vs actualFps which
                    // counts completed c.run() calls.
                    val runNanos = diagRunNanos.getAndSet(0)
                    val cbNanos = diagVideoCallbackNanos.getAndSet(0)
                    val drawNanos = diagDrawNanos.getAndSet(0)
                    val presentedWindow = diagPresentedWindow.getAndSet(0)
                    val coreMs = if (diagFramesRun > 0)
                        (runNanos - cbNanos).toDouble() / diagFramesRun / 1_000_000.0 else 0.0
                    val videoMs = if (presentedWindow > 0)
                        (cbNanos + drawNanos).toDouble() / presentedWindow / 1_000_000.0 else 0.0
                    val presentedFps = (presentedWindow / elapsedSec).toInt()
                    val coreMsStr = String.format(Locale.US, "%.1f", coreMs)
                    val videoMsStr = String.format(Locale.US, "%.1f", videoMs)
                    val diag = "$gameTitle audio: $actualFps frames a second, $audioUnderrunsTotal underruns, $avgBatch frames per batch, $currentAudioRate Hz, presentedFps=$presentedFps, coreMs=$coreMsStr, videoMs=$videoMsStr"
                    Log.i(TAG, "audio diag: $diag")
                    reportAudioDiag(diag)
                    diagFramesRun = 0
                    diagWindowStartNanos = nowNanos
                }
                val spent = System.nanoTime() - t0
                diagRunNanos.addAndGet(spent)
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
        // v1.4.13: runs ONLY on the video thread now. Never draw
        // after the surface is gone (surfaceDestroyed clears the
        // flag; stopVideoThread also clears any pending draw).
        if (!surfaceReady.get()) return
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
            // The surface can die mid-draw (activity finishing);
            // a failing unlock must not kill the video thread.
            try {
                surfaceView.holder.unlockCanvasAndPost(canvas)
            } catch (e: Exception) {
            }
        }
        // Only reached when the frame was actually posted: a null
        // canvas or an invalid surface returns before this line.
        framesDrawn.incrementAndGet()
    }

    // ---------- Input: remote (sideways) and standard gamepad ----------

    private fun isGamepad(event: KeyEvent): Boolean {
        val src = event.source
        return (src and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
            (src and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
    }

    /** Remote key -> RetroPad button, applying the sideways rotation.
     * Mute is Select — the same convention the WebView player uses,
     * so the 2-button systems (Game Boy, Atari) still get a Select. */
    private fun mapRemoteKey(code: Int): Int? = when (code) {
        KeyEvent.KEYCODE_DPAD_UP -> JoypadButton.LEFT
        KeyEvent.KEYCODE_DPAD_DOWN -> JoypadButton.RIGHT
        KeyEvent.KEYCODE_DPAD_LEFT -> JoypadButton.DOWN
        KeyEvent.KEYCODE_DPAD_RIGHT -> JoypadButton.UP
        KeyEvent.KEYCODE_CHANNEL_UP -> if (backHeld) JoypadButton.L else JoypadButton.A
        KeyEvent.KEYCODE_CHANNEL_DOWN -> if (backHeld) JoypadButton.R else JoypadButton.B
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER ->
            if (backHeld) JoypadButton.Y else JoypadButton.START
        KeyEvent.KEYCODE_MUTE -> JoypadButton.SELECT
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

        // D-pad, bottom-left. The tablet is held upright, so the
        // top button is game Up — unlike the remote, which is held
        // sideways and rotates its keys. The buttons feed the same
        // RetroPad direction ids the remote's rotated map produces.
        val dpad = RelativeLayout(this)
        dpad.layoutParams = FrameLayout.LayoutParams(dp(232), dp(232),
            Gravity.BOTTOM or Gravity.START).apply {
            leftMargin = dp(16)
            bottomMargin = dp(16)
        }
        val dpadUp = touchButton("▲", "D-pad up") { down ->
            if (down) pressed.add(JoypadButton.UP) else pressed.remove(JoypadButton.UP)
        }
        dpadUp.layoutParams = RelativeLayout.LayoutParams(dp(72), dp(72)).apply {
            addRule(RelativeLayout.CENTER_HORIZONTAL)
            addRule(RelativeLayout.ALIGN_PARENT_TOP)
        }
        val dpadDown = touchButton("▼", "D-pad down") { down ->
            if (down) pressed.add(JoypadButton.DOWN) else pressed.remove(JoypadButton.DOWN)
        }
        dpadDown.layoutParams = RelativeLayout.LayoutParams(dp(72), dp(72)).apply {
            addRule(RelativeLayout.CENTER_HORIZONTAL)
            addRule(RelativeLayout.ALIGN_PARENT_BOTTOM)
        }
        val dpadLeft = touchButton("◀", "D-pad left") { down ->
            if (down) pressed.add(JoypadButton.LEFT) else pressed.remove(JoypadButton.LEFT)
        }
        dpadLeft.layoutParams = RelativeLayout.LayoutParams(dp(72), dp(72)).apply {
            addRule(RelativeLayout.CENTER_VERTICAL)
            addRule(RelativeLayout.ALIGN_PARENT_START)
        }
        val dpadRight = touchButton("▶", "D-pad right") { down ->
            if (down) pressed.add(JoypadButton.RIGHT) else pressed.remove(JoypadButton.RIGHT)
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

        // Action diamond, bottom-right. PlayStation layout: Triangle
        // top, Cross bottom, Square left, Circle right. The 2D
        // systems use the same diamond but labelled with the
        // RetroPad names (X top, A bottom, Y left, B right) — the
        // buttons feed the same RetroPad ids either way (Cross/A,
        // Circle/B, Triangle/X, Square/Y).
        val actions = RelativeLayout(this)
        actions.layoutParams = FrameLayout.LayoutParams(dp(232), dp(232),
            Gravity.BOTTOM or Gravity.END).apply {
            rightMargin = dp(16)
            bottomMargin = dp(16)
        }
        val isPsx = gameSystem == "psx"
        val triangle = touchButton(if (isPsx) "△" else "X", if (isPsx) "Triangle button" else "X button") { down ->
            if (down) pressed.add(JoypadButton.X) else pressed.remove(JoypadButton.X)
        }
        triangle.layoutParams = RelativeLayout.LayoutParams(dp(72), dp(72)).apply {
            addRule(RelativeLayout.CENTER_HORIZONTAL)
            addRule(RelativeLayout.ALIGN_PARENT_TOP)
        }
        val cross = touchButton(if (isPsx) "✕" else "A", if (isPsx) "Cross button" else "A button") { down ->
            if (down) pressed.add(JoypadButton.A) else pressed.remove(JoypadButton.A)
        }
        cross.layoutParams = RelativeLayout.LayoutParams(dp(72), dp(72)).apply {
            addRule(RelativeLayout.CENTER_HORIZONTAL)
            addRule(RelativeLayout.ALIGN_PARENT_BOTTOM)
        }
        val square = touchButton(if (isPsx) "□" else "Y", if (isPsx) "Square button" else "Y button") { down ->
            if (down) pressed.add(JoypadButton.Y) else pressed.remove(JoypadButton.Y)
        }
        square.layoutParams = RelativeLayout.LayoutParams(dp(72), dp(72)).apply {
            addRule(RelativeLayout.CENTER_VERTICAL)
            addRule(RelativeLayout.ALIGN_PARENT_START)
        }
        val circle = touchButton(if (isPsx) "○" else "B", if (isPsx) "Circle button" else "B button") { down ->
            if (down) pressed.add(JoypadButton.B) else pressed.remove(JoypadButton.B)
        }
        circle.layoutParams = RelativeLayout.LayoutParams(dp(72), dp(72)).apply {
            addRule(RelativeLayout.CENTER_VERTICAL)
            addRule(RelativeLayout.ALIGN_PARENT_END)
        }
        actions.addView(triangle)
        actions.addView(cross)
        actions.addView(square)
        actions.addView(circle)
        root.addView(actions)

        // Start / Select, bottom-centre.
        val centerRow = LinearLayout(this)
        centerRow.orientation = LinearLayout.HORIZONTAL
        centerRow.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = dp(16)
        }
        val startBtn = touchButton("Start", "Start button", 16f) { down ->
            if (down) pressed.add(JoypadButton.START) else pressed.remove(JoypadButton.START)
        }
        startBtn.layoutParams = LinearLayout.LayoutParams(dp(72), dp(48)).apply {
            rightMargin = dp(12)
        }
        val selectBtn = touchButton("Select", "Select button", 16f) { down ->
            if (down) pressed.add(JoypadButton.SELECT) else pressed.remove(JoypadButton.SELECT)
        }
        selectBtn.layoutParams = LinearLayout.LayoutParams(dp(72), dp(48))
        centerRow.addView(startBtn)
        centerRow.addView(selectBtn)
        root.addView(centerRow)

        // Shoulders, top corners.
        val l1 = touchButton("L1", "L1 button", 16f) { down ->
            if (down) pressed.add(JoypadButton.L) else pressed.remove(JoypadButton.L)
        }
        l1.layoutParams = FrameLayout.LayoutParams(dp(96), dp(48),
            Gravity.TOP or Gravity.START).apply {
            leftMargin = dp(16)
            topMargin = dp(16)
        }
        root.addView(l1)
        val r1 = touchButton("R1", "R1 button", 16f) { down ->
            if (down) pressed.add(JoypadButton.R) else pressed.remove(JoypadButton.R)
        }
        r1.layoutParams = FrameLayout.LayoutParams(dp(96), dp(48),
            Gravity.TOP or Gravity.END).apply {
            rightMargin = dp(16)
            topMargin = dp(16)
        }
        root.addView(r1)
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
        try { loopThread?.join(1000) } catch (e: Exception) { }
        // Stop the video drain before closing the core (v1.4.13):
        // the emulation thread is joined, so no new frames arrive,
        // and stopping here guarantees no draw runs after the
        // surface is destroyed or the core is closed.
        stopVideoThread()
        // In-game saves first (v1.6.0): the core's battery RAM is
        // read while the core is still alive — close() unloads it.
        try { core?.let { saveSaveRam(it) } } catch (e: Exception) { Log.w(TAG, "save on close failed", e) }
        try { core?.close() } catch (e: Exception) { Log.w(TAG, "core close failed", e) }
        core = null
        // Stop the audio drain first (v1.4.12): it is the only other
        // thread touching the track, and it must be gone before the
        // track is released.
        stopAudioDrain()
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
