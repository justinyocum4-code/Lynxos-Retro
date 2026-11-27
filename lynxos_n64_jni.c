/*
 * Lynxos Retro — native Nintendo 64 player bridge (milestone M1).
 *
 * A minimal libretro frontend in plain C11: it dlopens a prebuilt N64
 * core (parallel_n64 or mupen64plus_next), drives it on ONE thread, and
 * hands its OpenGL output straight to the Activity's SurfaceView through
 * EGL. No pixels ever cross JNI on the video path: the core renders into
 * the window surface's default framebuffer and the bridge only swaps
 * buffers when the core says a frame is ready.
 *
 * The libretro API subset below is copied from libretro.h (RetroArch
 * master, checked 2026-11-27). Two values are easy to get wrong, so
 * they are spelled out: RETRO_ENVIRONMENT_SET_MEMORY_MAPS and
 * RETRO_ENVIRONMENT_GET_AUDIO_VIDEO_ENABLE carry the EXPERIMENTAL bit
 * (36 | 0x10000 and 47 | 0x10000), and in struct
 * retro_hw_render_callback the context_reset callback comes SECOND
 * (before get_current_framebuffer), with context_destroy after
 * cache_context.
 *
 * Threading: exactly one thread (the Kotlin core thread) ever calls
 * retro_run or any GL function. Surface changes arrive on the main
 * thread and are only ever flags; the core thread does the actual
 * EGL teardown/recreation. When the surface is gone the game pauses
 * (no retro_run, no audio) instead of running blind.
 *
 * If any hardware-render step fails, the load fails with a plain
 * reason in nativeGetLastError() and the Activity speaks it — never a
 * silent black screen, and never a fall back to the Angrylion software
 * renderer (it would look "working" at ~15 fps and confuse the test).
 */

#define _POSIX_C_SOURCE 200809L

#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <dlfcn.h>
#include <fcntl.h>
#include <EGL/egl.h>
#include <jni.h>
#include <limits.h>
#include <pthread.h>
#include <signal.h>
#include <stdatomic.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>
#include <unwind.h>

#define LOG_TAG "LynxosN64"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

/* ---------- libretro API subset (from libretro.h) ---------- */

typedef void (*retro_proc_address_t)(void);
typedef retro_proc_address_t (*retro_hw_get_proc_address_t)(const char *sym);
typedef uintptr_t (*retro_hw_get_current_framebuffer_t)(void);
typedef void (*retro_hw_context_reset_t)(void);
typedef bool (*retro_environment_t)(unsigned cmd, void *data);
typedef void (*retro_video_refresh_t)(const void *data, unsigned width,
                                      unsigned height, size_t pitch);
typedef void (*retro_audio_sample_t)(int16_t left, int16_t right);
typedef size_t (*retro_audio_sample_batch_t)(const int16_t *data, size_t frames);
typedef void (*retro_input_poll_t)(void);
typedef int16_t (*retro_input_state_t)(unsigned port, unsigned device,
                                       unsigned index, unsigned id);
typedef void (*retro_log_printf_t)(int level, const char *fmt, ...);

enum retro_hw_context_type {
    RETRO_HW_CONTEXT_NONE = 0,
    RETRO_HW_CONTEXT_OPENGL = 1,
    RETRO_HW_CONTEXT_OPENGLES2 = 2,
    RETRO_HW_CONTEXT_OPENGL_CORE = 3,
    RETRO_HW_CONTEXT_OPENGLES3 = 4,
    RETRO_HW_CONTEXT_OPENGLES_VERSION = 5,
    RETRO_HW_CONTEXT_VULKAN = 6,
    RETRO_HW_CONTEXT_DIRECT3D = 7
};

struct retro_hw_render_callback {
    enum retro_hw_context_type context_type;
    retro_hw_context_reset_t context_reset;
    retro_hw_get_current_framebuffer_t get_current_framebuffer;
    retro_hw_get_proc_address_t get_proc_address;
    bool depth;
    bool stencil;
    bool bottom_left_origin;
    unsigned version_major;
    unsigned version_minor;
    bool cache_context;
    retro_hw_context_reset_t context_destroy;
    bool debug_context;
};

struct retro_variable {
    const char *key;
    const char *value;
};

struct retro_log_callback {
    retro_log_printf_t log;
};

struct retro_message {
    const char *msg;
    unsigned frames;
};

struct retro_game_geometry {
    unsigned base_width;
    unsigned base_height;
    unsigned max_width;
    unsigned max_height;
    float aspect_ratio;
};

struct retro_system_timing {
    double fps;
    double sample_rate;
};

struct retro_system_av_info {
    struct retro_game_geometry geometry;
    struct retro_system_timing timing;
};

struct retro_game_info {
    const char *path;
    const void *data;
    size_t size;
    const char *meta;
};

struct retro_system_info {
    const char *library_name;
    const char *library_version;
    const char *valid_extensions;
    bool need_fullpath;
    bool block_extract;
};

#define RETRO_ENVIRONMENT_EXPERIMENTAL 0x10000u
#define LR_ENV_GET_CAN_DUPE 3
#define LR_ENV_SET_MESSAGE 6
#define LR_ENV_SHUTDOWN 7
#define LR_ENV_SET_PERFORMANCE_LEVEL 8
#define LR_ENV_GET_SYSTEM_DIRECTORY 9
#define LR_ENV_SET_PIXEL_FORMAT 10
#define LR_ENV_SET_INPUT_DESCRIPTORS 11
#define LR_ENV_SET_HW_RENDER 14
#define LR_ENV_GET_VARIABLE 15
#define LR_ENV_SET_VARIABLES 16
#define LR_ENV_GET_VARIABLE_UPDATE 17
#define LR_ENV_SET_SUPPORT_NO_GAME 18
#define LR_ENV_GET_LOG_INTERFACE 27
#define LR_ENV_GET_SAVE_DIRECTORY 31
#define LR_ENV_SET_SYSTEM_AV_INFO 32
#define LR_ENV_SET_CONTROLLER_INFO 35
#define LR_ENV_SET_MEMORY_MAPS (36 | RETRO_ENVIRONMENT_EXPERIMENTAL)
#define LR_ENV_SET_GEOMETRY 37
#define LR_ENV_GET_LANGUAGE 39
#define LR_ENV_SET_SERIALIZATION_QUIRKS 44
#define LR_ENV_GET_AUDIO_VIDEO_ENABLE (47 | RETRO_ENVIRONMENT_EXPERIMENTAL)
#define LR_ENV_GET_CORE_OPTIONS_VERSION 52
#define LR_ENV_SET_CORE_OPTIONS_DISPLAY 55
#define LR_ENV_GET_PREFERRED_HW_RENDER 56

#define LR_DEVICE_JOYPAD 1
#define LR_DEVICE_ANALOG 5
#define LR_ANALOG_LEFT 0
#define LR_ANALOG_RIGHT 1
#define LR_ANALOG_X 0
#define LR_ANALOG_Y 1
#define LR_MEMORY_SAVE_RAM 0
#define LR_HW_FRAME_BUFFER_VALID ((void *)-1)

/* RetroPad button ids (same numbering as libretro-kmp's JoypadButton). */
#define JP_B 0
#define JP_Y 1
#define JP_SELECT 2
#define JP_START 3
#define JP_UP 4
#define JP_DOWN 5
#define JP_LEFT 6
#define JP_RIGHT 7
#define JP_A 8
#define JP_X 9
#define JP_L 10
#define JP_R 11
#define JP_L2 12
#define JP_R2 13

/* ---------- state ---------- */

static char g_last_error[512] = "";
static char g_system_dir[PATH_MAX] = "";
static char g_save_dir[PATH_MAX] = "";
static char g_game_path[PATH_MAX] = "";
static void *g_game_data = NULL;   /* ROM bytes: kept until nativeClose (libretro contract) */
static size_t g_game_size = 0;
static char g_title[256] = "";
/* Last bridge step, kept in C as well as SharedPreferences (v1.4.9):
 * the crash handler can only read plain memory, never call into Java. */
static char g_last_bridge_step[128] = "";

static void set_error(const char *msg) {
    snprintf(g_last_error, sizeof g_last_error, "%s", msg ? msg : "Unknown error.");
    LOGE("%s", g_last_error);
}

/* Core entry points (dlsym'd in nativeLoadCore). */
static unsigned (*g_retro_api_version)(void);
static void (*g_retro_set_environment)(retro_environment_t);
static void (*g_retro_set_video_refresh)(retro_video_refresh_t);
static void (*g_retro_set_audio_sample)(retro_audio_sample_t);
static void (*g_retro_set_audio_sample_batch)(retro_audio_sample_batch_t);
static void (*g_retro_set_input_poll)(retro_input_poll_t);
static void (*g_retro_set_input_state)(retro_input_state_t);
static void (*g_retro_init)(void);
static void (*g_retro_deinit)(void);
static void (*g_retro_get_system_info)(struct retro_system_info *info);
static void (*g_retro_get_system_av_info)(struct retro_system_av_info *info);
static void (*g_retro_set_controller_port_device)(unsigned port, unsigned device);
static void (*g_retro_reset)(void);
static void (*g_retro_run)(void);
static bool (*g_retro_load_game)(const struct retro_game_info *game);
static void (*g_retro_unload_game)(void);
static void *(*g_retro_get_memory_data)(unsigned id);
static size_t (*g_retro_get_memory_size)(unsigned id);

static void *g_core_handle;
static bool g_core_loaded;
static bool g_game_loaded;
static bool g_closed;
static bool g_shutdown;
static struct retro_system_av_info g_av;

/* Core options set from Kotlin before loadGame (load-time only). */
#define MAX_OPTIONS 32
static struct {
    char *key;
    char *value;
} g_options[MAX_OPTIONS];
static int g_option_count;

static const char *find_option(const char *key) {
    for (int i = 0; i < g_option_count; i++) {
        if (strcmp(g_options[i].key, key) == 0) return g_options[i].value;
    }
    return NULL;
}

/* Input state, written from Kotlin on key events, read on the core
 * thread inside retro_run. Aligned 16-bit reads/writes are atomic on
 * ARM64, so no lock is needed for these. */
static volatile int16_t g_pad[16];
static volatile int16_t g_analog_lx, g_analog_ly, g_analog_rx, g_analog_ry;
static int g_input_log_count;

/* EGL state. Only the core thread touches EGL objects; the mutex
 * serialises the surface flags the main thread sets. */
static ANativeWindow *g_window;
static ANativeWindow *g_pending_window;
static volatile int g_surface_lost;
static volatile int g_surface_new;
static EGLDisplay g_display = EGL_NO_DISPLAY;
static EGLSurface g_surface = EGL_NO_SURFACE;
static EGLContext g_context = EGL_NO_CONTEXT;
static pthread_mutex_t g_gl_mutex = PTHREAD_MUTEX_INITIALIZER;
static struct retro_hw_render_callback g_hw;
static bool g_hw_active;
static int g_hw_gl_version = 2;
/* Deferred context_reset (v1.4.9): context_reset must run AFTER
 * retro_load_game returns, once the core's first_context_reset is
 * set — calling it inside handle_set_hw_render (mid-load, before
 * the ROM is loaded) runs the video plugin's GL init on
 * uninitialized core state and crashes (v1.4.8). handle_set_hw_render
 * only arms this flag; nativeLoadGame fires the reset. */
static bool g_context_reset_pending;

static volatile int g_frame_ready;
static int g_video_cb_count;
static long g_fps_frames;
static long g_fps_second;
/* Video diagnostics for the Kotlin 5 s check: total video callbacks
 * from the core vs frames actually presented with eglSwapBuffers.
 * A running game with callbacks but no presented frames means the
 * screen is not ready; no callbacks at all means the core is not
 * producing video. */
static volatile long g_video_cb_total;
static volatile long g_frames_presented;

/* Audio upcall back into Kotlin. The core thread is a Java thread, so
 * GetEnv normally succeeds; the attach path covers a core-spawned
 * thread, and the TLS destructor detaches only threads we attached. */
static JavaVM *g_vm;
static jobject g_activity_global;
static jmethodID g_on_audio_batch;
static jmethodID g_on_bridge_step;
static pthread_key_t g_jni_key;
static int g_jni_key_created;

static void jni_detach(void *v) {
    (void)v;
    if (g_vm) (*g_vm)->DetachCurrentThread(g_vm);
}

static JNIEnv *get_jni_env(void) {
    JNIEnv *env = NULL;
    if (!g_vm) return NULL;
    if ((*g_vm)->GetEnv(g_vm, (void **)&env, JNI_VERSION_1_6) == JNI_OK) return env;
    if ((*g_vm)->AttachCurrentThread(g_vm, &env, NULL) != JNI_OK) return NULL;
    if (g_jni_key_created) pthread_setspecific(g_jni_key, (void *)1);
    return env;
}

static void log_egl(const char *where) {
    EGLint err = eglGetError();
    if (err != EGL_SUCCESS) LOGE("EGL %s: error 0x%x", where, err);
}

/* ---------- libretro callbacks ---------- */

static void core_log(int level, const char *fmt, ...) {
    char buf[1024];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(buf, sizeof buf, fmt, ap);
    va_end(ap);
    int prio = ANDROID_LOG_INFO;
    if (level == 0) prio = ANDROID_LOG_DEBUG;
    else if (level == 2) prio = ANDROID_LOG_WARN;
    else if (level >= 3) prio = ANDROID_LOG_ERROR;
    __android_log_print(prio, LOG_TAG, "core: %s", buf);
}

static void video_cb(const void *data, unsigned width, unsigned height, size_t pitch) {
    (void)pitch;
    if (g_video_cb_count < 5) {
        g_video_cb_count++;
        LOGI("video_cb #%d: data=%p %ux%u", g_video_cb_count, data, width, height);
    }
    g_video_cb_total++;
    if (data == LR_HW_FRAME_BUFFER_VALID) {
        g_frame_ready = 1;
    } else if (data == NULL) {
        /* Duplicated frame: skip the present. */
    } else {
        /* A real software frame (Angrylion only — not used in M1).
         * Never dereference it here; the HW path never copies pixels. */
    }
}

static void audio_sample_cb(int16_t left, int16_t right) {
    (void)left;
    (void)right;
    /* The N64 cores use the batch callback; single samples are ignored. */
}

static size_t audio_batch_cb(const int16_t *data, size_t frames) {
    if (!data || frames == 0) return 0;
    if (!g_activity_global || !g_on_audio_batch) return frames;
    JNIEnv *env = get_jni_env();
    if (!env) return frames;
    jshortArray arr = (*env)->NewShortArray(env, (jsize)(frames * 2));
    if (!arr) return frames;
    (*env)->SetShortArrayRegion(env, arr, 0, (jsize)(frames * 2), (const jshort *)data);
    (*env)->CallVoidMethod(env, g_activity_global, g_on_audio_batch, arr, (jint)frames);
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionDescribe(env);
        (*env)->ExceptionClear(env);
    }
    (*env)->DeleteLocalRef(env, arr);
    return frames;
}

/* Bridge-step breadcrumb (v1.4.7): record the last graphics-setup
 * step in SharedPreferences via the activity, so a native crash
 * inside the core's GL init still leaves a durable trace naming the
 * step it died in. Called from handle_set_hw_render /
 * create_gl_context_locked, i.e. on the core thread during
 * retro_load_game — before any Java code could report anything. The
 * write uses commit() (in onBridgeStep) because the crash can land
 * milliseconds after the write. */
static void record_bridge_step(const char *step) {
    LOGI("bridge step: %s", step);
    /* Keep a C-side copy too: the crash handler (v1.4.9) can only
     * read plain memory, never call into Java. */
    if (step) snprintf(g_last_bridge_step, sizeof g_last_bridge_step, "%s", step);
    if (!g_activity_global || !g_on_bridge_step) return;
    JNIEnv *env = get_jni_env();
    if (!env) return;
    jstring jstep = (*env)->NewStringUTF(env, step);
    if (!jstep) return;
    (*env)->CallVoidMethod(env, g_activity_global, g_on_bridge_step, jstep);
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionDescribe(env);
        (*env)->ExceptionClear(env);
    }
    (*env)->DeleteLocalRef(env, jstep);
}

/* ---------- native crash recorder (v1.4.9) ----------
 *
 * A native crash (SIGSEGV in a core) kills the whole process, so no
 * Java code can report it — the v1.4.7/1.4.8 silent deaths. A signal
 * handler writes the crash spot to filesDir/n64-crash.txt, and
 * MainActivity reads it on the next launch and reports it. The
 * handler uses ONLY async-signal-safe calls plus write(). The file
 * is opened inside the handler, on the crash (open() is not
 * signal-safe, but neither is the _Unwind_Backtrace below — the
 * same pragmatic choice as v1.4.9). The backtrace uses
 * _Unwind_Backtrace into a fixed static buffer — not strictly
 * async-signal-safe, but the standard pragmatic choice; it never
 * allocates on the common path. Library load bases are captured at
 * init by parsing /proc/self/maps, so the handler only subtracts.
 * The file is NOT opened at init (v1.4.11): the old code truncated
 * it on every launch, so testing one game wiped the previous crash
 * record before anyone read it. A record now survives launches
 * until a new crash replaces it. After recording, the handler
 * restores the default disposition and re-raises, so the process
 * still dies normally (Android writes its tombstone too). */

#define CRASH_MAX_FRAMES 32
#define CRASH_MAX_LIBS 6
static int g_crash_fd = -1;
static char g_crash_path[PATH_MAX] = "";
static char g_crash_core[64] = "";
static volatile sig_atomic_t g_crash_handling = 0;
static struct {
    const char *name;
    uintptr_t start;
    uintptr_t end;
} g_crash_libs[CRASH_MAX_LIBS];
static int g_crash_lib_count = 0;

/* Write a string with write() only — safe inside a signal handler. */
static void crash_write_str(const char *s) {
    if (g_crash_fd < 0 || !s) return;
    size_t n = 0;
    while (s[n]) n++;
    while (n > 0) {
        ssize_t w = write(g_crash_fd, s, n);
        if (w <= 0) return;
        s += w;
        n -= (size_t)w;
    }
}

/* Write a small integer in decimal, with no libc formatting (snprintf
 * is not async-signal-safe). */
static void crash_write_dec(int v) {
    if (v == 0) { crash_write_str("0"); return; }
    if (v < 0) { crash_write_str("-"); v = -v; }
    char rev[12];
    int n = 0;
    while (v > 0 && n < 12) { rev[n++] = (char)('0' + (v % 10)); v /= 10; }
    char buf[13];
    int i = 0;
    while (n > 0) buf[i++] = rev[--n];
    buf[i] = '\0';
    crash_write_str(buf);
}

/* Write a pointer-sized value in hex ("0x..."), no libc formatting. */
static void crash_write_hex(uintptr_t v) {
    crash_write_str("0x");
    if (v == 0) { crash_write_str("0"); return; }
    char rev[16];
    int n = 0;
    while (v && n < 16) {
        unsigned d = (unsigned)(v & 0xF);
        rev[n++] = (char)(d < 10 ? '0' + d : 'a' + (d - 10));
        v >>= 4;
    }
    char buf[17];
    int i = 0;
    while (n > 0) buf[i++] = rev[--n];
    buf[i] = '\0';
    crash_write_str(buf);
}

struct crash_unwind_state {
    uintptr_t *pcs;
    int count;
    int max;
};

static _Unwind_Reason_Code crash_unwind_cb(struct _Unwind_Context *ctx, void *arg) {
    struct crash_unwind_state *st = (struct crash_unwind_state *)arg;
    if (st->count >= st->max) return _URC_END_OF_STACK;
    uintptr_t ip = (uintptr_t)_Unwind_GetIP(ctx);
    if (ip != 0) st->pcs[st->count++] = ip;
    return _URC_NO_REASON;
}

static void crash_handler(int sig, siginfo_t *info, void *ucontext) {
    (void)ucontext;
    if (g_crash_handling) {
        /* A second signal while recording: die at once, no recursion. */
        signal(sig, SIG_DFL);
        raise(sig);
        _exit(128 + sig);
    }
    g_crash_handling = 1;
    /* Open the file here, on the crash — not at init (v1.4.11):
     * the previous record survives launches until a new crash
     * replaces it. If the open fails the write below is skipped
     * (g_crash_fd stays -1) and the process still dies normally. */
    if (g_crash_fd < 0 && g_crash_path[0]) {
        g_crash_fd = open(g_crash_path, O_WRONLY | O_CREAT | O_TRUNC, 0644);
    }
    if (g_crash_fd >= 0) {
        crash_write_str("signal ");
        crash_write_dec(sig);
        crash_write_str("\ncode ");
        crash_write_dec(info ? info->si_code : 0);
        crash_write_str("\nfault ");
        crash_write_hex(info ? (uintptr_t)info->si_addr : 0);
        crash_write_str("\ncore ");
        crash_write_str(g_crash_core[0] ? g_crash_core : "(none)");
        crash_write_str("\ntitle ");
        crash_write_str(g_title[0] ? g_title : "(none)");
        crash_write_str("\nstep ");
        crash_write_str(g_last_bridge_step[0] ? g_last_bridge_step : "(none)");
        crash_write_str("\nbases:");
        for (int i = 0; i < g_crash_lib_count; i++) {
            crash_write_str(" ");
            crash_write_str(g_crash_libs[i].name);
            crash_write_str(" ");
            crash_write_hex(g_crash_libs[i].start);
            crash_write_str("-");
            crash_write_hex(g_crash_libs[i].end);
        }
        crash_write_str("\ntrace:\n");
        uintptr_t pcs[CRASH_MAX_FRAMES];
        struct crash_unwind_state st;
        st.pcs = pcs;
        st.count = 0;
        st.max = CRASH_MAX_FRAMES;
        _Unwind_Backtrace(crash_unwind_cb, &st);
        for (int i = 0; i < st.count; i++) {
            uintptr_t pc = pcs[i];
            int found = 0;
            for (int j = 0; j < g_crash_lib_count; j++) {
                if (pc >= g_crash_libs[j].start && pc < g_crash_libs[j].end) {
                    crash_write_str(g_crash_libs[j].name);
                    crash_write_str("+");
                    crash_write_hex(pc - g_crash_libs[j].start);
                    crash_write_str("\n");
                    found = 1;
                    break;
                }
            }
            if (!found) {
                crash_write_hex(pc);
                crash_write_str("\n");
            }
        }
    }
    /* Die normally: restore the default disposition and re-raise so
     * Android still writes its tombstone. */
    signal(sig, SIG_DFL);
    raise(sig);
    _exit(128 + sig);
}

static void crash_install_handlers(void) {
    static int installed = 0;
    if (installed) return;
    installed = 1;
    struct sigaction sa;
    memset(&sa, 0, sizeof sa);
    sa.sa_sigaction = crash_handler;
    sa.sa_flags = SA_SIGINFO;
    sigemptyset(&sa.sa_mask);
    sigaction(SIGSEGV, &sa, NULL);
    sigaction(SIGBUS, &sa, NULL);
    sigaction(SIGABRT, &sa, NULL);
    sigaction(SIGFPE, &sa, NULL);
    sigaction(SIGILL, &sa, NULL);
}

/* Capture each .so's load base (lowest mapped address) at init time
 * by parsing /proc/self/maps. Offsets in the crash file are PC minus
 * base, so addr2line resolves them against the shipped .so files.
 * Only the directory of the first sighting of a basename is kept:
 * the same basename could exist in two places (e.g. libEGL in
 * /system and /vendor) and the loaded one is the first mapped. */
static void crash_capture_bases(void) {
    static const char *names[CRASH_MAX_LIBS] = {
        "liblynxos_n64_jni.so",
        "parallel_n64_libretro_android.so",
        "mupen64plus_next_gles3_libretro_android.so",
        "libEGL.so",
        "libGLESv3.so",
        "libGLESv2.so"
    };
    static char first_dir[CRASH_MAX_LIBS][PATH_MAX];
    g_crash_lib_count = 0;
    FILE *f = fopen("/proc/self/maps", "r");
    if (!f) return;
    char line[1024];
    while (fgets(line, sizeof line, f)) {
        unsigned long start = 0, end = 0;
        if (sscanf(line, "%lx-%lx", &start, &end) != 2) continue;
        char *path = strchr(line, '/');
        if (!path) continue;
        /* Trim the trailing newline for an exact basename match. */
        size_t plen = strlen(path);
        while (plen > 0 && (path[plen - 1] == '\n' || path[plen - 1] == '\r')) path[--plen] = '\0';
        for (int i = 0; i < CRASH_MAX_LIBS; i++) {
            if (!names[i]) continue;
            size_t nl = strlen(names[i]);
            if (plen < nl || strcmp(path + plen - nl, names[i]) != 0) continue;
            /* Directory of this sighting (everything before the name). */
            char dir[PATH_MAX];
            size_t dlen = plen - nl;
            if (dlen >= sizeof dir) dlen = sizeof dir - 1;
            memcpy(dir, path, dlen);
            dir[dlen] = '\0';
            int idx = -1;
            for (int j = 0; j < g_crash_lib_count; j++) {
                if (g_crash_libs[j].name == names[i]) { idx = j; break; }
            }
            if (idx < 0) {
                if (g_crash_lib_count >= CRASH_MAX_LIBS) break;
                idx = g_crash_lib_count++;
                g_crash_libs[idx].name = names[i];
                g_crash_libs[idx].start = (uintptr_t)start;
                g_crash_libs[idx].end = (uintptr_t)end;
                snprintf(first_dir[idx], sizeof first_dir[idx], "%s", dir);
            } else if (strcmp(first_dir[idx], dir) == 0) {
                if ((uintptr_t)start < g_crash_libs[idx].start) g_crash_libs[idx].start = (uintptr_t)start;
                if ((uintptr_t)end > g_crash_libs[idx].end) g_crash_libs[idx].end = (uintptr_t)end;
            }
            break;
        }
    }
    fclose(f);
}

/* Resolve the crash file path and capture the .so bases at init.
 * The file itself is NOT opened here (v1.4.11): opening it with
 * O_TRUNC on every launch wiped the previous crash record before
 * anyone read it. The handler opens it (O_TRUNC) only when a crash
 * actually happens. If anything here fails the recorder simply
 * stays off — it must never break a launch. */
static void crash_setup(JNIEnv *env, jobject activity) {
    if (g_crash_fd >= 0) {
        close(g_crash_fd);
        g_crash_fd = -1;
    }
    jclass clazz = (*env)->GetObjectClass(env, activity);
    if (clazz) {
        jmethodID getFilesDir = (*env)->GetMethodID(env, clazz, "getFilesDir", "()Ljava/io/File;");
        if (!getFilesDir) {
            if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
        } else {
            jobject dirObj = (*env)->CallObjectMethod(env, activity, getFilesDir);
            if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
            if (dirObj) {
                jclass fileClazz = (*env)->GetObjectClass(env, dirObj);
                jmethodID getAbsPath = fileClazz ? (*env)->GetMethodID(env, fileClazz, "getAbsolutePath", "()Ljava/lang/String;") : NULL;
                if (!getAbsPath) {
                    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
                } else {
                    jstring jpath = (jstring)(*env)->CallObjectMethod(env, dirObj, getAbsPath);
                    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
                    if (jpath) {
                        const char *cpath = (*env)->GetStringUTFChars(env, jpath, NULL);
                        if (cpath) {
                            snprintf(g_crash_path, sizeof g_crash_path, "%s/n64-crash.txt", cpath);
                            (*env)->ReleaseStringUTFChars(env, jpath, cpath);
                        }
                        (*env)->DeleteLocalRef(env, jpath);
                    }
                }
                (*env)->DeleteLocalRef(env, dirObj);
            }
        }
    }
    crash_capture_bases();
    crash_install_handlers();
    LOGI("crash recorder armed: %s (file opens on crash)", g_crash_path);
}

static void input_poll_cb(void) {
    /* State is read directly in input_state_cb; nothing to poll. */
}

static int16_t input_state_cb(unsigned port, unsigned device, unsigned index, unsigned id) {
    if (port != 0) return 0;
    if (g_input_log_count < 20) {
        g_input_log_count++;
        LOGI("input_state #%d: device=%u index=%u id=%u",
             g_input_log_count, device, index, id);
    }
    if (device == LR_DEVICE_JOYPAD) {
        if (id < 16) return g_pad[id];
        return 0;
    }
    if (device == LR_DEVICE_ANALOG) {
        if (index == LR_ANALOG_LEFT)
            return id == LR_ANALOG_X ? g_analog_lx : (id == LR_ANALOG_Y ? g_analog_ly : 0);
        if (index == LR_ANALOG_RIGHT)
            return id == LR_ANALOG_X ? g_analog_rx : (id == LR_ANALOG_Y ? g_analog_ry : 0);
        return 0;
    }
    return 0;
}

/* get_proc_address: the core asks for GL entry points; EGL answers.
 * A NULL return is logged once per symbol name. */
#define MAX_MISSING_SYMS 64
static char g_missing_syms[MAX_MISSING_SYMS][64];
static int g_missing_sym_count;

static retro_proc_address_t lynxos_get_proc_address(const char *sym) {
    retro_proc_address_t p = (retro_proc_address_t)eglGetProcAddress(sym);
    if (!p) {
        int seen = 0;
        for (int i = 0; i < g_missing_sym_count; i++) {
            if (strcmp(g_missing_syms[i], sym) == 0) { seen = 1; break; }
        }
        if (!seen) {
            LOGW("get_proc_address NULL: %s", sym);
            if (g_missing_sym_count < MAX_MISSING_SYMS) {
                snprintf(g_missing_syms[g_missing_sym_count],
                         sizeof g_missing_syms[0], "%s", sym);
                g_missing_sym_count++;
            }
        }
    }
    return p;
}

/* The core renders straight into the window surface's default
 * framebuffer; there is no FBO and no blit. */
static uintptr_t lynxos_get_current_framebuffer(void) {
    return 0;
}

/* Pre-warm: resolve the GL entry points the cores will ask for
 * through lynxos_get_proc_address, before loadGame hands control to
 * the core. A missing one is a clean, diagnosable failure — not a
 * segfault inside the core later (a NULL proc address called by the
 * core kills the whole process, and no Java reporting ever runs).
 *
 * The lists are the intersection of the OpenGL ES core API for that
 * version with the function names in the shipped cores' string tables
 * (parallel_n64 + mupen64plus_next .so files, as shipped in v1.4.6):
 * ES2 set from both cores, ES3 set (ES 3.0 core only) from mupen.
 * Extension names (EXT/OES/KHR/...) are deliberately excluded:
 * eglGetProcAddress may legitimately return NULL for an unsupported
 * extension, and the cores check extensions themselves. ES 3.1/3.2
 * functions are excluded for the same reason — the context is ES 3.0.
 * Every name here is a core function of the context version it is
 * checked under, so a conformant driver must resolve it: a NULL here
 * is a real failure, never a false positive. */
static const char *PREWARM_SYMS_ES2[] = {
    "glActiveTexture", "glAttachShader", "glBindAttribLocation", "glBindBuffer",
    "glBindFramebuffer", "glBindRenderbuffer", "glBindTexture", "glBlendColor",
    "glBlendEquationSeparate", "glBlendFunc", "glBlendFuncSeparate", "glBufferData",
    "glBufferSubData", "glCheckFramebufferStatus", "glClear", "glClearColor",
    "glClearDepthf", "glColorMask", "glCompileShader", "glCopyTexImage2D",
    "glCopyTexSubImage2D", "glCreateProgram", "glCreateShader", "glCullFace",
    "glDeleteBuffers", "glDeleteFramebuffers", "glDeleteProgram", "glDeleteRenderbuffers",
    "glDeleteShader", "glDeleteTextures", "glDepthFunc", "glDepthMask",
    "glDepthRangef", "glDetachShader", "glDisable", "glDisableVertexAttribArray",
    "glDrawArrays", "glDrawElements", "glEnable", "glEnableVertexAttribArray",
    "glFinish", "glFlush", "glFramebufferRenderbuffer", "glFramebufferTexture2D",
    "glFrontFace", "glGenBuffers", "glGenFramebuffers", "glGenRenderbuffers",
    "glGenTextures", "glGenerateMipmap", "glGetError", "glGetFloatv",
    "glGetFramebufferAttachmentParameteriv", "glGetIntegerv", "glGetProgramInfoLog", "glGetProgramiv",
    "glGetShaderInfoLog", "glGetShaderiv", "glGetString", "glGetTexParameteriv",
    "glGetUniformLocation", "glGetVertexAttribPointerv", "glGetVertexAttribiv", "glIsProgram",
    "glLineWidth", "glLinkProgram", "glPixelStorei", "glPolygonOffset",
    "glReadPixels", "glRenderbufferStorage", "glScissor", "glShaderSource",
    "glStencilFunc", "glStencilMask", "glStencilOp", "glTexImage2D",
    "glTexParameterf", "glTexParameteri", "glTexSubImage2D", "glUniform1f",
    "glUniform1i", "glUniform1iv", "glUniform2f", "glUniform2i",
    "glUniform3f", "glUniform3fv", "glUniform4f", "glUniform4fv",
    "glUniform4i", "glUseProgram", "glVertexAttrib1f", "glVertexAttrib4f",
    "glVertexAttrib4fv", "glVertexAttribPointer", "glViewport"
};

static const char *PREWARM_SYMS_ES3[] = {
    "glBindBufferBase", "glBindVertexArray", "glBlitFramebuffer", "glClientWaitSync",
    "glDeleteSync", "glDeleteVertexArrays", "glDrawBuffers", "glFenceSync",
    "glFlushMappedBufferRange", "glGenVertexArrays", "glGetActiveUniformBlockiv", "glGetActiveUniformsiv",
    "glGetProgramBinary", "glGetStringi", "glGetUniformBlockIndex", "glGetUniformIndices",
    "glInvalidateFramebuffer", "glMapBufferRange", "glProgramBinary", "glProgramParameteri",
    "glReadBuffer", "glTexStorage2D", "glUniformBlockBinding", "glUnmapBuffer"
};

static bool prewarm_syms(const char **syms, size_t count) {
    for (size_t i = 0; i < count; i++) {
        void *p = (void *)eglGetProcAddress(syms[i]);
        if (!p) {
            LOGE("pre-warm: %s is missing", syms[i]);
            snprintf(g_last_error, sizeof g_last_error,
                     "The graphics could not start: %s is missing.", syms[i]);
            return false;
        }
    }
    return true;
}

/* The context version create_gl_context_locked really created (2 or
 * 3 — the ES3 request falls back to ES2 when the device has no ES3
 * config). The ES3 set is only checked on a real ES3 context: on an
 * ES2 context those names legitimately resolve to NULL. */
static int g_gl_actual_version = 2;

static bool prewarm_gl(void) {
    if (!prewarm_syms(PREWARM_SYMS_ES2,
                      sizeof PREWARM_SYMS_ES2 / sizeof PREWARM_SYMS_ES2[0])) {
        return false;
    }
    if (g_gl_actual_version >= 3) {
        if (!prewarm_syms(PREWARM_SYMS_ES3,
                          sizeof PREWARM_SYMS_ES3 / sizeof PREWARM_SYMS_ES3[0])) {
            return false;
        }
    }
    return true;
}

/* Create the EGL context + window surface for the already-set window.
 * Caller must hold g_gl_mutex and run on the core thread. */
static bool create_gl_context_locked(void) {
    if (g_display == EGL_NO_DISPLAY) {
        g_display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
        log_egl("eglGetDisplay");
        if (g_display == EGL_NO_DISPLAY) {
            set_error("No graphics display is available.");
            return false;
        }
        if (!eglInitialize(g_display, NULL, NULL)) {
            log_egl("eglInitialize");
            set_error("The graphics system could not start.");
            return false;
        }
    }
    int version = g_hw_gl_version >= 3 ? 3 : 2;
    EGLint renderable = (version == 3) ? EGL_OPENGL_ES3_BIT : EGL_OPENGL_ES2_BIT;
    const EGLint attribs[] = {
        EGL_SURFACE_TYPE, EGL_WINDOW_BIT,
        EGL_RENDERABLE_TYPE, renderable,
        EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8, EGL_ALPHA_SIZE, 8,
        EGL_DEPTH_SIZE, 24, EGL_STENCIL_SIZE, 8,
        EGL_NONE
    };
    EGLConfig config = NULL;
    EGLint ncfg = 0;
    if (!eglChooseConfig(g_display, attribs, &config, 1, &ncfg) || ncfg < 1) {
        log_egl("eglChooseConfig");
        if (version == 3) {
            /* F2 (v1.4.8): never hand an ES2 context to a core that
               asked for ES3 — it segfaults on its first ES3 call.
               Fail cleanly so the player reports it instead. */
            set_error("The device has no ES3 graphics mode.");
            return false;
        }
        /* Fall back to an ES2 renderable type and try once more. */
        const EGLint attribs2[] = {
            EGL_SURFACE_TYPE, EGL_WINDOW_BIT,
            EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
            EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8, EGL_ALPHA_SIZE, 8,
            EGL_DEPTH_SIZE, 24, EGL_STENCIL_SIZE, 8,
            EGL_NONE
        };
        if (!eglChooseConfig(g_display, attribs2, &config, 1, &ncfg) || ncfg < 1) {
            log_egl("eglChooseConfig (fallback)");
            set_error("No graphics mode is available on this device.");
            return false;
        }
        version = 2;
    }
    /* The version that really got created (F2 v1.4.8: an ES3 request
     * no longer falls back to ES2 — it fails cleanly instead);
     * prewarm_gl checks symbols against this, not the requested
     * version. */
    g_gl_actual_version = version;
    /* F2 (v1.4.8): record the version that really got created, so a
       future silent death names it. */
    {
        char step[48];
        snprintf(step, sizeof step, "graphics mode chosen (GLES %d)", version);
        record_bridge_step(step);
    }
    EGLint depth = 0, stencil = 0, visual = 0;
    eglGetConfigAttrib(g_display, config, EGL_DEPTH_SIZE, &depth);
    eglGetConfigAttrib(g_display, config, EGL_STENCIL_SIZE, &stencil);
    eglGetConfigAttrib(g_display, config, EGL_NATIVE_VISUAL_ID, &visual);
    LOGI("EGL config: depth %d stencil %d visual %d (GLES %d)",
         depth, stencil, visual, version);
    ANativeWindow_setBuffersGeometry(g_window, 0, 0, visual);
    LOGI("surface size: %dx%d",
         ANativeWindow_getWidth(g_window), ANativeWindow_getHeight(g_window));
    g_surface = eglCreateWindowSurface(g_display, config, g_window, NULL);
    log_egl("eglCreateWindowSurface");
    if (g_surface == EGL_NO_SURFACE) {
        set_error("The game screen could not be created.");
        return false;
    }
    const EGLint ctx_attribs[] = { EGL_CONTEXT_CLIENT_VERSION, version, EGL_NONE };
    g_context = eglCreateContext(g_display, config, EGL_NO_CONTEXT, ctx_attribs);
    log_egl("eglCreateContext");
    if (g_context == EGL_NO_CONTEXT) {
        set_error("The graphics context could not be created.");
        return false;
    }
    if (!eglMakeCurrent(g_display, g_surface, g_surface, g_context)) {
        log_egl("eglMakeCurrent");
        set_error("The graphics context could not be started.");
        return false;
    }
    if (!eglSwapInterval(g_display, 1)) log_egl("eglSwapInterval");
    LOGI("GL context ready (GLES %d)", version);
    record_bridge_step("graphics context ready");
    return true;
}

/* Tear the GL objects down. Caller must hold g_gl_mutex and run on
 * the core thread (the context is current there). Releases the window. */
static void destroy_gl_context_locked(void) {
    if (g_context != EGL_NO_CONTEXT && g_hw.context_destroy) {
        g_hw.context_destroy();
    }
    if (g_display != EGL_NO_DISPLAY) {
        eglMakeCurrent(g_display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        log_egl("eglMakeCurrent(none)");
        if (g_context != EGL_NO_CONTEXT) {
            eglDestroyContext(g_display, g_context);
            log_egl("eglDestroyContext");
            g_context = EGL_NO_CONTEXT;
        }
        if (g_surface != EGL_NO_SURFACE) {
            eglDestroySurface(g_display, g_surface);
            log_egl("eglDestroySurface");
            g_surface = EGL_NO_SURFACE;
        }
    }
    if (g_window) {
        ANativeWindow_release(g_window);
        g_window = NULL;
    }
}

static bool handle_set_hw_render(void *data) {
    struct retro_hw_render_callback *cb = (struct retro_hw_render_callback *)data;
    if (!cb) return false;
    LOGI("SET_HW_RENDER: type %d version %u.%u depth %d stencil %d "
         "bottom_left %d cache %d",
         cb->context_type, cb->version_major, cb->version_minor,
         cb->depth, cb->stencil, cb->bottom_left_origin, cb->cache_context);
    int version;
    switch (cb->context_type) {
        case RETRO_HW_CONTEXT_OPENGLES2:
            version = 2;
            break;
        case RETRO_HW_CONTEXT_OPENGLES3:
            version = 3;
            break;
        case RETRO_HW_CONTEXT_OPENGLES_VERSION:
            version = cb->version_major >= 3 ? 3 : 2;
            break;
        default:
            set_error("This game needs a graphics mode this player does not support.");
            return false;
    }
    /* The window arrives via nativeSetSurface as g_pending_window and
     * is normally promoted by the first nativeRunFrame — but
     * SET_HW_RENDER is called during retro_load_game, BEFORE any
     * frame runs, so the first load always saw a NULL window here
     * and failed with "The game screen was not ready." Promote the
     * pending window now so the EGL context can be created during
     * load. nativeRunFrame's own promotion then finds nothing left
     * to do (g_pending_window is NULL), so ownership transfers once. */
    pthread_mutex_lock(&g_gl_mutex);
    if (!g_window && g_pending_window) {
        g_window = g_pending_window;
        g_pending_window = NULL;
        g_surface_new = 0;
    }
    pthread_mutex_unlock(&g_gl_mutex);
    if (!g_window) {
        set_error("The game screen was not ready.");
        return false;
    }
    /* F4 (v1.4.8): a 0-size window makes a 0-size EGL surface;
       fail cleanly instead of finding out later in retro_run. */
    int winW = ANativeWindow_getWidth(g_window);
    int winH = ANativeWindow_getHeight(g_window);
    if (winW <= 0 || winH <= 0) {
        set_error("The game screen has no size yet.");
        return false;
    }
    {
        char step[48];
        snprintf(step, sizeof step, "game screen %dx%d", winW, winH);
        record_bridge_step(step);
    }
    record_bridge_step("starting graphics setup");
    /* F1 (v1.4.8): the two frontend-owned callbacks must be written
       back into the CORE's struct as well as our copy — glsm reads
       its own struct, and with NULL pointers there context_reset
       segfaults (the v1.4.7 silent death). */
    g_hw = *cb; /* copy: the core's struct is filled in, we add ours */
    cb->get_proc_address = lynxos_get_proc_address;
    cb->get_current_framebuffer = lynxos_get_current_framebuffer;
    g_hw.get_proc_address = lynxos_get_proc_address;
    g_hw.get_current_framebuffer = lynxos_get_current_framebuffer;
    g_hw_gl_version = version;
    g_hw_active = true;
    pthread_mutex_lock(&g_gl_mutex);
    bool ok = create_gl_context_locked();
    if (ok && !prewarm_gl()) {
        destroy_gl_context_locked();
        ok = false;
    }
    pthread_mutex_unlock(&g_gl_mutex);
    if (!ok) {
        g_hw_active = false;
        return false;
    }
    record_bridge_step("graphics functions checked");
    /* v1.4.9: do NOT call context_reset here. SET_HW_RENDER fires
     * in the middle of retro_load_game, before the ROM is loaded
     * and before either core's emulator init (both cores gate
     * their init on first_context_reset, set only at the END of
     * retro_load_game). Calling it now runs the video plugin's GL
     * init on uninitialized core state and crashes (v1.4.8). The
     * reset is deferred to nativeLoadGame, after retro_load_game
     * returns — the moment the libretro contract and both cores
     * expect. The EGL context stays created here. */
    g_context_reset_pending = true;
    record_bridge_step("graphics context ready, reset deferred");
    LOGI("HW render ready, context_reset deferred");
    return true;
}

static bool environment_cb(unsigned cmd, void *data) {
    switch (cmd) {
        case LR_ENV_GET_CAN_DUPE:
            if (data) *(bool *)data = true;
            return true;
        case LR_ENV_SET_MESSAGE: {
            struct retro_message *m = (struct retro_message *)data;
            if (m && m->msg) LOGI("core message: %s", m->msg);
            return true;
        }
        case LR_ENV_SHUTDOWN:
            g_shutdown = true;
            return true;
        case LR_ENV_SET_PERFORMANCE_LEVEL:
        case LR_ENV_SET_INPUT_DESCRIPTORS:
        case LR_ENV_SET_CONTROLLER_INFO:
        case LR_ENV_SET_MEMORY_MAPS:
        case LR_ENV_SET_SERIALIZATION_QUIRKS:
        case LR_ENV_SET_CORE_OPTIONS_DISPLAY:
        case LR_ENV_SET_SUPPORT_NO_GAME:
        case LR_ENV_SET_PIXEL_FORMAT:
            /* Accepted; the core carries on without them. */
            return true;
        case LR_ENV_GET_SYSTEM_DIRECTORY:
            if (data) *(const char **)data = g_system_dir;
            return true;
        case LR_ENV_GET_SAVE_DIRECTORY:
            if (data) *(const char **)data = g_save_dir;
            return true;
        case LR_ENV_GET_LOG_INTERFACE:
            if (data) ((struct retro_log_callback *)data)->log = core_log;
            return true;
        case LR_ENV_GET_VARIABLE: {
            /* Per the header this call "returns true if the
             * environment call is available, even if the key is not
             * found" — but every real frontend answers false for an
             * unknown key so the core falls back to its default, and
             * the cores do exactly that. */
            struct retro_variable *var = (struct retro_variable *)data;
            if (!var || !var->key) return true;
            const char *value = find_option(var->key);
            if (!value) return false;
            var->value = value;
            LOGI("GET_VARIABLE %s = %s", var->key, value);
            return true;
        }
        case LR_ENV_SET_VARIABLES: {
            const struct retro_variable *vars = (const struct retro_variable *)data;
            int n = 0;
            if (vars) {
                while (vars[n].key) n++;
            }
            LOGI("SET_VARIABLES: %d options offered by the core", n);
            return true;
        }
        case LR_ENV_GET_VARIABLE_UPDATE:
            /* Options are load-time only; they never change mid-game. */
            if (data) *(bool *)data = false;
            return true;
        case LR_ENV_SET_SYSTEM_AV_INFO:
            if (data) {
                g_av = *(struct retro_system_av_info *)data;
                LOGI("SET_SYSTEM_AV_INFO: fps=%.2f sample_rate=%.0f "
                     "geometry %ux%u (max %ux%u)",
                     g_av.timing.fps, g_av.timing.sample_rate,
                     g_av.geometry.base_width, g_av.geometry.base_height,
                     g_av.geometry.max_width, g_av.geometry.max_height);
            }
            return true;
        case LR_ENV_SET_GEOMETRY:
            if (data) {
                g_av.geometry = *(struct retro_game_geometry *)data;
                LOGI("SET_GEOMETRY: %ux%u (max %ux%u)",
                     g_av.geometry.base_width, g_av.geometry.base_height,
                     g_av.geometry.max_width, g_av.geometry.max_height);
            }
            return true;
        case LR_ENV_GET_LANGUAGE:
            if (data) *(unsigned *)data = 0; /* English */
            return true;
        case LR_ENV_GET_AUDIO_VIDEO_ENABLE:
            if (data) *(int *)data = 3; /* video + audio */
            return true;
        case LR_ENV_GET_CORE_OPTIONS_VERSION:
            /* Legacy options (the mode both cores use here). */
            if (data) *(unsigned *)data = 0;
            return true;
        case LR_ENV_SET_HW_RENDER:
            return handle_set_hw_render(data);
        case LR_ENV_GET_PREFERRED_HW_RENDER:
            /* Declined: both cores take the GL path without it. */
            return false;
        default:
            return false;
    }
}

/* ---------- save RAM ---------- */

static void srm_path(char *out, size_t out_size) {
    char safe[256];
    size_t n = 0;
    for (size_t i = 0; g_title[i] && n < sizeof safe - 1; i++) {
        char c = g_title[i];
        safe[n++] = ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') ||
                     (c >= '0' && c <= '9') || c == '.' || c == '_' || c == '-')
                    ? c : '_';
    }
    safe[n] = '\0';
    if (n == 0) snprintf(safe, sizeof safe, "game");
    snprintf(out, out_size, "%s/%s.srm", g_save_dir, safe);
}

static void load_ram(void) {
    if (!g_core_loaded || !g_retro_get_memory_data || !g_retro_get_memory_size) return;
    void *data = g_retro_get_memory_data(LR_MEMORY_SAVE_RAM);
    size_t size = g_retro_get_memory_size(LR_MEMORY_SAVE_RAM);
    if (!data || size == 0) {
        LOGI("no save RAM exposed by this core");
        return;
    }
    char path[PATH_MAX];
    srm_path(path, sizeof path);
    FILE *f = fopen(path, "rb");
    if (!f) {
        LOGI("no save file yet (%s)", path);
        return;
    }
    size_t got = fread(data, 1, size, f);
    fclose(f);
    LOGI("loaded %zu save bytes from %s", got, path);
}

static void save_ram(void) {
    if (!g_core_loaded || !g_retro_get_memory_data || !g_retro_get_memory_size) return;
    void *data = g_retro_get_memory_data(LR_MEMORY_SAVE_RAM);
    size_t size = g_retro_get_memory_size(LR_MEMORY_SAVE_RAM);
    if (!data || size == 0) {
        LOGI("no save RAM to store");
        return;
    }
    char path[PATH_MAX];
    srm_path(path, sizeof path);
    FILE *f = fopen(path, "wb");
    if (!f) {
        LOGW("could not write save file %s", path);
        return;
    }
    size_t wrote = fwrite(data, 1, size, f);
    fclose(f);
    LOGI("saved %zu bytes to %s", wrote, path);
}

/* ---------- JNI entry points ---------- */

JNIEXPORT jboolean JNICALL
Java_com_lynxosretro_app_NativeN64PlayerActivity_nativeInit(JNIEnv *env, jobject thiz, jobject activity) {
    (void)thiz;
    if ((*env)->GetJavaVM(env, &g_vm) != JNI_OK || !g_vm) {
        set_error("The Nintendo 64 player could not start.");
        return JNI_FALSE;
    }
    if (!g_jni_key_created) {
        if (pthread_key_create(&g_jni_key, jni_detach) == 0) g_jni_key_created = 1;
    }
    jobject local = (*env)->NewGlobalRef(env, activity);
    if (!local) {
        set_error("The Nintendo 64 player could not start.");
        return JNI_FALSE;
    }
    if (g_activity_global) (*env)->DeleteGlobalRef(env, g_activity_global);
    g_activity_global = local;
    jclass clazz = (*env)->GetObjectClass(env, activity);
    g_on_audio_batch = (*env)->GetMethodID(env, clazz, "onAudioBatch", "([SI)V");
    if (!g_on_audio_batch) {
        LOGE("onAudioBatch method not found");
        set_error("The Nintendo 64 player could not start.");
        return JNI_FALSE;
    }
    /* Bridge-step breadcrumb (v1.4.7): diagnostic only — if the
     * method is missing the player still works, the step just won't
     * be recorded. A failed lookup leaves an exception pending, so
     * clear it. */
    g_on_bridge_step = (*env)->GetMethodID(env, clazz, "onBridgeStep", "(Ljava/lang/String;)V");
    if (!g_on_bridge_step) {
        if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
        LOGE("onBridgeStep method not found; bridge steps will not be recorded");
    }
    /* Crash recorder (v1.4.9): arm the signal handler now that the
     * activity is known. A failure here never fails the launch. */
    crash_setup(env, activity);
    LOGI("nativeInit ok");
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_lynxosretro_app_NativeN64PlayerActivity_nativeSetSurface(JNIEnv *env, jobject thiz, jobject surface) {
    (void)thiz;
    pthread_mutex_lock(&g_gl_mutex);
    if (surface == NULL) {
        g_surface_lost = 1;
    } else {
        ANativeWindow *w = ANativeWindow_fromSurface(env, surface);
        if (w) {
            if (g_pending_window) ANativeWindow_release(g_pending_window);
            g_pending_window = w;
            g_surface_new = 1;
            if (g_window) g_surface_lost = 1; /* old surface goes away first */
        }
    }
    pthread_mutex_unlock(&g_gl_mutex);
}

JNIEXPORT jboolean JNICALL
Java_com_lynxosretro_app_NativeN64PlayerActivity_nativeLoadCore(JNIEnv *env, jobject thiz,
        jstring jcore_path, jstring jsystem_dir, jstring jsave_dir) {
    (void)thiz;
    /* Fresh launch in a reused process: clear the previous game's state. */
    if (g_core_handle) {
        dlclose(g_core_handle);
        g_core_handle = NULL;
    }
    g_core_loaded = false;
    g_closed = false;
    g_shutdown = false;
    g_game_loaded = false;
    g_frame_ready = 0;
    g_video_cb_count = 0;
    g_video_cb_total = 0;
    g_frames_presented = 0;
    g_input_log_count = 0;
    g_missing_sym_count = 0;
    g_fps_frames = 0;
    g_fps_second = 0;
    g_hw_active = false;
    g_context_reset_pending = false;
    g_surface_lost = 0;
    g_surface_new = 0;
    memset(&g_av, 0, sizeof g_av);
    for (int i = 0; i < g_option_count; i++) {
        free(g_options[i].key);
        free(g_options[i].value);
    }
    g_option_count = 0;

    const char *core_path = (*env)->GetStringUTFChars(env, jcore_path, NULL);
    const char *system_dir = (*env)->GetStringUTFChars(env, jsystem_dir, NULL);
    const char *save_dir = (*env)->GetStringUTFChars(env, jsave_dir, NULL);
    if (system_dir) snprintf(g_system_dir, sizeof g_system_dir, "%s", system_dir);
    if (save_dir) snprintf(g_save_dir, sizeof g_save_dir, "%s", save_dir);
    if (!core_path) {
        set_error("No game core file to load.");
        goto out;
    }
    /* Crash recorder: remember which core was attempted, so a crash
     * during load still names it. */
    if (strstr(core_path, "parallel")) {
        snprintf(g_crash_core, sizeof g_crash_core, "parallel");
    } else if (strstr(core_path, "mupen")) {
        snprintf(g_crash_core, sizeof g_crash_core, "mupen");
    } else {
        snprintf(g_crash_core, sizeof g_crash_core, "(unknown)");
    }
    g_core_handle = dlopen(core_path, RTLD_NOW | RTLD_LOCAL);
    if (!g_core_handle) {
        LOGE("dlopen failed: %s", dlerror());
        set_error("The Nintendo 64 game core could not be loaded.");
        goto out;
    }
#define LOAD_SYM(name) do { \
        g_##name = dlsym(g_core_handle, #name); \
        if (!g_##name) { \
            snprintf(g_last_error, sizeof g_last_error, \
                     "The game core is missing its %s entry point.", #name); \
            LOGE("%s", g_last_error); \
            dlclose(g_core_handle); \
            g_core_handle = NULL; \
            goto out; \
        } \
    } while (0)
    LOAD_SYM(retro_api_version);
    LOAD_SYM(retro_set_environment);
    LOAD_SYM(retro_set_video_refresh);
    LOAD_SYM(retro_set_audio_sample);
    LOAD_SYM(retro_set_audio_sample_batch);
    LOAD_SYM(retro_set_input_poll);
    LOAD_SYM(retro_set_input_state);
    LOAD_SYM(retro_init);
    LOAD_SYM(retro_deinit);
    LOAD_SYM(retro_get_system_info);
    LOAD_SYM(retro_get_system_av_info);
    LOAD_SYM(retro_set_controller_port_device);
    LOAD_SYM(retro_reset);
    LOAD_SYM(retro_run);
    LOAD_SYM(retro_load_game);
    LOAD_SYM(retro_unload_game);
    LOAD_SYM(retro_get_memory_data);
    LOAD_SYM(retro_get_memory_size);
#undef LOAD_SYM
    if (g_retro_api_version() != 1) {
        set_error("The game core uses an unknown interface version.");
        dlclose(g_core_handle);
        g_core_handle = NULL;
        goto out;
    }
    g_retro_set_environment(environment_cb);
    g_retro_set_video_refresh(video_cb);
    g_retro_set_audio_sample(audio_sample_cb);
    g_retro_set_audio_sample_batch(audio_batch_cb);
    g_retro_set_input_poll(input_poll_cb);
    g_retro_set_input_state(input_state_cb);
    g_retro_init();
    {
        struct retro_system_info info;
        memset(&info, 0, sizeof info);
        g_retro_get_system_info(&info);
        LOGI("core: %s %s (need_fullpath=%d)",
             info.library_name ? info.library_name : "?",
             info.library_version ? info.library_version : "?",
             info.need_fullpath);
    }
    g_core_loaded = true;
    LOGI("core loaded: %s", core_path);
out:
    if (core_path) (*env)->ReleaseStringUTFChars(env, jcore_path, core_path);
    if (system_dir) (*env)->ReleaseStringUTFChars(env, jsystem_dir, system_dir);
    if (save_dir) (*env)->ReleaseStringUTFChars(env, jsave_dir, save_dir);
    return g_core_loaded ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_lynxosretro_app_NativeN64PlayerActivity_nativeSetOption(JNIEnv *env, jobject thiz,
        jstring jkey, jstring jvalue) {
    (void)thiz;
    if (g_option_count >= MAX_OPTIONS) {
        LOGW("too many core options, ignoring one");
        return;
    }
    const char *key = (*env)->GetStringUTFChars(env, jkey, NULL);
    const char *value = (*env)->GetStringUTFChars(env, jvalue, NULL);
    if (!key || !value) {
        if (key) (*env)->ReleaseStringUTFChars(env, jkey, key);
        if (value) (*env)->ReleaseStringUTFChars(env, jvalue, value);
        return;
    }
    /* A later set wins for the same key. */
    for (int i = 0; i < g_option_count; i++) {
        if (strcmp(g_options[i].key, key) == 0) {
            free(g_options[i].value);
            g_options[i].value = strdup(value);
            goto done;
        }
    }
    g_options[g_option_count].key = strdup(key);
    g_options[g_option_count].value = strdup(value);
    g_option_count++;
done:
    if (key) (*env)->ReleaseStringUTFChars(env, jkey, key);
    if (value) (*env)->ReleaseStringUTFChars(env, jvalue, value);
}

JNIEXPORT jboolean JNICALL
Java_com_lynxosretro_app_NativeN64PlayerActivity_nativeLoadGame(JNIEnv *env, jobject thiz,
        jstring jpath, jstring jtitle) {
    (void)thiz;
    if (!g_core_loaded) {
        set_error("The game core was not loaded.");
        return JNI_FALSE;
    }
    const char *path = (*env)->GetStringUTFChars(env, jpath, NULL);
    const char *title = (*env)->GetStringUTFChars(env, jtitle, NULL);
    if (path) snprintf(g_game_path, sizeof g_game_path, "%s", path);
    if (title) snprintf(g_title, sizeof g_title, "%s", title);
    /* The cores need the ROM itself, not just its path. Mupen
     * declares need_fullpath=false, so it reads game->data: the
     * NULL/0 we passed before gave it a 0-byte ROM, and its ROM
     * parser runs on a buffer it assumes holds a header. Parallel
     * declares need_fullpath=true and reads the file itself, so
     * the path stays set too. The buffer lives until nativeClose:
     * the libretro contract keeps game->data valid until unload. */
    free(g_game_data);
    g_game_data = NULL;
    g_game_size = 0;
    FILE *rom = fopen(g_game_path, "rb");
    long rom_size = -1;
    if (rom) {
        if (fseek(rom, 0, SEEK_END) == 0) rom_size = ftell(rom);
        if (rom_size > 0) {
            fseek(rom, 0, SEEK_SET);
            g_game_data = malloc((size_t)rom_size);
            if (g_game_data && fread(g_game_data, 1, (size_t)rom_size, rom) != (size_t)rom_size) {
                free(g_game_data);
                g_game_data = NULL;
            }
        }
        fclose(rom);
    }
    if (!g_game_data || rom_size <= 0) {
        free(g_game_data);
        g_game_data = NULL;
        set_error(rom_size == 0 ? "The game file is empty." : "The game file could not be read.");
        if (path) (*env)->ReleaseStringUTFChars(env, jpath, path);
        if (title) (*env)->ReleaseStringUTFChars(env, jtitle, title);
        return JNI_FALSE;
    }
    g_game_size = (size_t)rom_size;
    struct retro_game_info info;
    info.path = g_game_path;
    info.data = g_game_data;
    info.size = g_game_size;
    info.meta = NULL;
    /* A stale pending flag from a failed earlier load must not fire
     * a reset for this one; handle_set_hw_render arms it again
     * during this load if the core asks for hardware rendering. */
    g_context_reset_pending = false;
    bool ok = g_retro_load_game(&info);
    if (path) (*env)->ReleaseStringUTFChars(env, jpath, path);
    if (title) (*env)->ReleaseStringUTFChars(env, jtitle, title);
    if (!ok) {
        if (!g_last_error[0]) set_error("The game could not be loaded.");
        return JNI_FALSE;
    }
    if (!g_hw_active) {
        /* Both N64 cores render with OpenGL; a load that never asked
         * for hardware rendering would run blind. Fail loudly. */
        set_error("The game did not ask for hardware graphics.");
        return JNI_FALSE;
    }
    /* v1.4.9: the deferred context_reset (see handle_set_hw_render).
     * retro_load_game has returned, so first_context_reset is set
     * and the cores' emulator init runs before the video plugin's
     * GL init — the order RetroArch uses. */
    if (g_context_reset_pending) {
        g_context_reset_pending = false;
        if (g_hw.context_reset) {
            record_bridge_step("core graphics starting");
            g_hw.context_reset();
            record_bridge_step("core graphics started");
        }
    }
    g_game_loaded = true;
    g_retro_set_controller_port_device(0, LR_DEVICE_JOYPAD);
    load_ram();
    {
        struct retro_system_av_info av;
        memset(&av, 0, sizeof av);
        g_retro_get_system_av_info(&av);
        if (av.timing.fps > 0) g_av = av;
        LOGI("av_info: fps=%.2f sample_rate=%.0f geometry %ux%u (max %ux%u)",
             g_av.timing.fps, g_av.timing.sample_rate,
             g_av.geometry.base_width, g_av.geometry.base_height,
             g_av.geometry.max_width, g_av.geometry.max_height);
    }
    LOGI("game loaded: %s", g_game_path);
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_lynxosretro_app_NativeN64PlayerActivity_nativeRunFrame(JNIEnv *env, jobject thiz) {
    (void)env;
    (void)thiz;
    if (!g_core_loaded || !g_game_loaded || g_shutdown) return JNI_FALSE;
    pthread_mutex_lock(&g_gl_mutex);
    if (g_surface_lost) {
        g_surface_lost = 0;
        destroy_gl_context_locked(); /* releases the old window */
    }
    if (g_surface_new && g_pending_window) {
        g_window = g_pending_window;
        g_pending_window = NULL;
        g_surface_new = 0;
    }
    if (g_hw_active && g_context == EGL_NO_CONTEXT && g_window) {
        if (!create_gl_context_locked()) {
            pthread_mutex_unlock(&g_gl_mutex);
            return JNI_FALSE; /* reason already in g_last_error */
        }
        if (g_hw.context_reset) g_hw.context_reset();
        LOGI("GL context recreated, context_reset called");
    }
    int have_context = (g_context != EGL_NO_CONTEXT);
    pthread_mutex_unlock(&g_gl_mutex);
    if (!have_context) {
        /* No screen to draw to (Home pressed, rotating): pause the
         * game instead of running blind. The next surface recreates
         * the context and play resumes. */
        struct timespec ts;
        ts.tv_sec = 0;
        ts.tv_nsec = 16000000L;
        nanosleep(&ts, NULL);
        return JNI_TRUE;
    }
    g_frame_ready = 0;
    g_retro_run();
    if (g_frame_ready) {
        pthread_mutex_lock(&g_gl_mutex);
        if (g_context != EGL_NO_CONTEXT && g_surface != EGL_NO_SURFACE) {
            if (eglSwapBuffers(g_display, g_surface)) g_frames_presented++;
            else log_egl("eglSwapBuffers");
        }
        pthread_mutex_unlock(&g_gl_mutex);
        g_fps_frames++;
        long now = (long)(time(NULL));
        if (g_fps_second == 0) g_fps_second = now;
        if (now - g_fps_second >= 1) {
            LOGI("fps: %ld (target %.2f)", g_fps_frames, g_av.timing.fps);
            g_fps_frames = 0;
            g_fps_second = now;
        }
    }
    return g_shutdown ? JNI_FALSE : JNI_TRUE;
}

JNIEXPORT jdouble JNICALL
Java_com_lynxosretro_app_NativeN64PlayerActivity_nativeGetFps(JNIEnv *env, jobject thiz) {
    (void)env;
    (void)thiz;
    return g_av.timing.fps;
}

JNIEXPORT jdouble JNICALL
Java_com_lynxosretro_app_NativeN64PlayerActivity_nativeGetSampleRate(JNIEnv *env, jobject thiz) {
    (void)env;
    (void)thiz;
    return g_av.timing.sample_rate;
}

JNIEXPORT jfloat JNICALL
Java_com_lynxosretro_app_NativeN64PlayerActivity_nativeGetAspectRatio(JNIEnv *env, jobject thiz) {
    (void)env;
    (void)thiz;
    /* libretro: aspect_ratio 0 means "use base_width/base_height".
     * The geometry arrives via SET_SYSTEM_AV_INFO / SET_GEOMETRY
     * during load and retro_get_system_av_info at the end of
     * nativeLoadGame, so this is only meaningful after load. */
    if (g_av.geometry.aspect_ratio > 0) return g_av.geometry.aspect_ratio;
    if (g_av.geometry.base_width > 0 && g_av.geometry.base_height > 0)
        return (float)g_av.geometry.base_width / (float)g_av.geometry.base_height;
    return 4.0f / 3.0f;
}

JNIEXPORT void JNICALL
Java_com_lynxosretro_app_NativeN64PlayerActivity_nativeSetButton(JNIEnv *env, jobject thiz,
        jint id, jboolean pressed) {
    (void)env;
    (void)thiz;
    if (id >= 0 && id < 16) g_pad[id] = pressed ? 1 : 0;
}

JNIEXPORT void JNICALL
Java_com_lynxosretro_app_NativeN64PlayerActivity_nativeSetAnalog(JNIEnv *env, jobject thiz,
        jint index, jint x, jint y) {
    (void)env;
    (void)thiz;
    if (x < -32767) x = -32767;
    if (x > 32767) x = 32767;
    if (y < -32767) y = -32767;
    if (y > 32767) y = 32767;
    if (index == LR_ANALOG_LEFT) {
        g_analog_lx = (int16_t)x;
        g_analog_ly = (int16_t)y;
    } else if (index == LR_ANALOG_RIGHT) {
        g_analog_rx = (int16_t)x;
        g_analog_ry = (int16_t)y;
    }
}

JNIEXPORT jstring JNICALL
Java_com_lynxosretro_app_NativeN64PlayerActivity_nativeGetLastError(JNIEnv *env, jobject thiz) {
    (void)thiz;
    return (*env)->NewStringUTF(env, g_last_error);
}

/* Video diagnostics for the Kotlin 5 s check (see g_video_cb_total /
 * g_frames_presented above). */
JNIEXPORT jlong JNICALL
Java_com_lynxosretro_app_NativeN64PlayerActivity_nativeGetVideoCallbacks(JNIEnv *env, jobject thiz) {
    (void)env;
    (void)thiz;
    return (jlong)g_video_cb_total;
}

JNIEXPORT jlong JNICALL
Java_com_lynxosretro_app_NativeN64PlayerActivity_nativeGetFramesPresented(JNIEnv *env, jobject thiz) {
    (void)env;
    (void)thiz;
    return (jlong)g_frames_presented;
}

JNIEXPORT void JNICALL
Java_com_lynxosretro_app_NativeN64PlayerActivity_nativeClose(JNIEnv *env, jobject thiz) {
    (void)thiz;
    /* Idempotent: the Kotlin side calls this once from the core
     * thread's finally block, but a second call must be harmless. */
    static pthread_mutex_t close_mutex = PTHREAD_MUTEX_INITIALIZER;
    pthread_mutex_lock(&close_mutex);
    if (g_closed) {
        pthread_mutex_unlock(&close_mutex);
        return;
    }
    g_closed = true;
    pthread_mutex_unlock(&close_mutex);
    save_ram();
    if (g_core_loaded) {
        if (g_game_loaded) {
            g_retro_unload_game();
            g_game_loaded = false;
        }
        g_retro_deinit();
        g_core_loaded = false;
    }
    pthread_mutex_lock(&g_gl_mutex);
    if (g_context != EGL_NO_CONTEXT || g_surface != EGL_NO_SURFACE) {
        destroy_gl_context_locked();
    }
    if (g_pending_window) {
        ANativeWindow_release(g_pending_window);
        g_pending_window = NULL;
    }
    pthread_mutex_unlock(&g_gl_mutex);
    if (g_core_handle) {
        dlclose(g_core_handle);
        g_core_handle = NULL;
    }
    free(g_game_data);
    g_game_data = NULL;
    g_game_size = 0;
    if (g_activity_global) {
        (*env)->DeleteGlobalRef(env, g_activity_global);
        g_activity_global = NULL;
    }
    g_on_audio_batch = NULL;
    /* The N64 player exited cleanly: forget which core/game was
     * loaded, so a later native crash in another player (PS1) is
     * not mislabelled as an N64 crash. The crash file itself is
     * left alone — MainActivity owns reading it. */
    g_crash_core[0] = '\0';
    g_title[0] = '\0';
    LOGI("nativeClose done");
}
