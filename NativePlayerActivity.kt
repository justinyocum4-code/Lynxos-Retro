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
import dev.kotlinds.libretrokmp.VideoFrame
import java.io.File
import java.io.FileOutputStream
import java.util.Collections
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

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
 * works with its own buttons, no rotation. On a touchscreen device (the
 * tablet) there is also an on-screen touch controller: D-pad bottom-left,
 * action diamond bottom-right, Start/Select bottom-centre, L1/R1 top
 * corners. It is hidden on the TV, which has no touchscreen.
 */
class NativePlayerActivity : Activity(), SurfaceHolder.Callback {

    companion object {
        const val EXTRA_GAME_PATH = "gamePath"
        const val EXTRA_GAME_TITLE = "gameTitle"
        private const val TAG = "NativePlayer"
        private const val CORE_ASSET = "native/pcsx_rearmed_libretro_android.so"
        private const val CORE_NAME = "pcsx_rearmed_libretro_android.so"
        private const val CORE_VERSION = "1"
        private const val BACK_HOLD_EXIT_MS = 500L
        private const val START_WATCHDOG_MS = 30_000L
        private const val VIDEO_CHECK_MS = 5_000L
        private const val TRIANGLE_TAP_MS = 100L
        // A TalkBack double-tap (click) cannot hold a button, so it
        // does a short timed tap instead of a hold.
        private const val TOUCH_TAP_MS = 150L
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

    private var frameBitmap: Bitmap? = null
    private val paint = Paint().apply { isFilterBitmap = false }

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

    // Start watchdog, same shape as the N64 player: if the frame
    // loop has not started 30 s after the core thread starts, the
    // thread is hung inside a blocking loadGame call (a hang is not
    // an exception, so the try/catch never fires). The finish is
    // delayed 5 s after the spoken warning and guarded on running
    // still being false: finishPlayer stops TTS, so finishing
    // immediately would cut the warning off unheard.
    private val startWatchdog = Runnable {
        if (!running.get()) {
            speakAndToast("The PlayStation game is taking too long to start.")
            mainHandler.postDelayed({
                if (!running.get()) {
                    reportOutcome("hang", "The game did not start within 30 seconds.", hard = true)
                    finishPlayer()
                }
            }, 5000L)
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
                speakAndToast("The PlayStation game is running but no picture has been drawn yet. $reason")
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
        try { MainActivity.reportPlayerOutcome("psx", kind, reason, hard) } catch (e: Exception) {
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
            framesPresented.incrementAndGet()
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
                val coreFile = ensureCoreFile()
                val c = LibretroCore(coreFile.absolutePath, frontend)
                core = c
                setLoadStage("loading the game")
                c.loadGame(gamePath)
                c.setControllerPortDevice(0, Device.JOYPAD)
                val fps = c.avInfo.timing.fps
                val sampleRate = c.avInfo.timing.sampleRate.toInt()
                runOnUiThread { startAudio(sampleRate) }
                speak("Now playing $gameTitle, PlayStation. " + controlSummary())
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
                    finish()
                }
            }
        }.start()
        mainHandler.postDelayed(startWatchdog, START_WATCHDOG_MS)
    }

    private fun controlSummary(): String {
        val base = "Hold the remote sideways, top to the left. " +
            "Channel up is Cross, channel down is Circle. Tap OK for Start. " +
            "Tap Back is Triangle. Hold Back and press OK for Square, " +
            "channel up for L1, channel down for R1. " +
            "Hold Back alone for half a second to leave."
        return if (packageManager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)) {
            base + " On this tablet there are also touch buttons on the screen: D-pad bottom left, action buttons bottom right."
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

    private fun startLoop(fps: Double) {
        running.set(true)
        setLoadStage("running")
        mainHandler.removeCallbacks(startWatchdog)
        mainHandler.postDelayed(videoCheck, VIDEO_CHECK_MS)
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

        // Action diamond, bottom-right: Triangle top, Cross
        // bottom, Square left, Circle right — the PlayStation
        // layout. Each feeds the RetroPad id the remote produces
        // for the same button (Cross A, Circle B, Triangle X,
        // Square Y).
        val actions = RelativeLayout(this)
        actions.layoutParams = FrameLayout.LayoutParams(dp(232), dp(232),
            Gravity.BOTTOM or Gravity.END).apply {
            rightMargin = dp(16)
            bottomMargin = dp(16)
        }
        val triangle = touchButton("△", "Triangle button") { down ->
            if (down) pressed.add(JoypadButton.X) else pressed.remove(JoypadButton.X)
        }
        triangle.layoutParams = RelativeLayout.LayoutParams(dp(72), dp(72)).apply {
            addRule(RelativeLayout.CENTER_HORIZONTAL)
            addRule(RelativeLayout.ALIGN_PARENT_TOP)
        }
        val cross = touchButton("✕", "Cross button") { down ->
            if (down) pressed.add(JoypadButton.A) else pressed.remove(JoypadButton.A)
        }
        cross.layoutParams = RelativeLayout.LayoutParams(dp(72), dp(72)).apply {
            addRule(RelativeLayout.CENTER_HORIZONTAL)
            addRule(RelativeLayout.ALIGN_PARENT_BOTTOM)
        }
        val square = touchButton("□", "Square button") { down ->
            if (down) pressed.add(JoypadButton.Y) else pressed.remove(JoypadButton.Y)
        }
        square.layoutParams = RelativeLayout.LayoutParams(dp(72), dp(72)).apply {
            addRule(RelativeLayout.CENTER_VERTICAL)
            addRule(RelativeLayout.ALIGN_PARENT_START)
        }
        val circle = touchButton("○", "Circle button") { down ->
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

    private fun finishPlayer() {
        if (closed.getAndSet(true)) return
        // A deliberate exit (or a reported failure, whose outcome is
        // already recorded): clear the launch marker so MainActivity
        // does not announce this return as a silent crash.
        try { MainActivity.clearPlayerLaunchMarker() } catch (e: Exception) { }
        running.set(false)
        mainHandler.removeCallbacks(exitRunnable)
        mainHandler.removeCallbacks(startWatchdog)
        mainHandler.removeCallbacks(videoCheck)
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
