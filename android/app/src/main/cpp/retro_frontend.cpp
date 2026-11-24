#include <jni.h>
#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <EGL/egl.h>
#include <GLES2/gl2.h>

#include <dlfcn.h>
#include <unistd.h>

#include <atomic>
#include <cstdarg>
#include <cstring>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#include "libretro.h"

#define LOG_TAG "PS1Frontend"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

typedef void (*fn_void)(void);
typedef void (*fn_system_info)(struct retro_system_info *);
typedef void (*fn_system_av_info)(struct retro_system_av_info *);
typedef bool (*fn_load_game)(const struct retro_game_info *);
typedef void (*fn_set_env)(retro_environment_t);
typedef void (*fn_set_video)(retro_video_refresh_t);
typedef void (*fn_set_audio_sample)(retro_audio_sample_t);
typedef void (*fn_set_audio_batch)(retro_audio_sample_batch_t);
typedef void (*fn_set_poll)(retro_input_poll_t);
typedef void (*fn_set_input)(retro_input_state_t);

static JavaVM *g_vm = nullptr;
static jobject g_host = nullptr;
static jmethodID g_midRate = nullptr;
static jmethodID g_midData = nullptr;
static jmethodID g_midError = nullptr;

static void *g_core = nullptr;
static fn_system_info fn_get_system_info = nullptr;
static fn_void fn_init = nullptr;
static fn_void fn_deinit = nullptr;
static fn_set_env fn_set_environment = nullptr;
static fn_set_video fn_set_video_refresh = nullptr;
static fn_set_audio_sample pfn_set_audio_sample = nullptr;
static fn_set_audio_batch pfn_set_audio_batch = nullptr;
static fn_set_poll fn_set_input_poll = nullptr;
static fn_set_input fn_set_input_state = nullptr;
static fn_load_game pfn_load_game = nullptr;
static fn_void fn_unload_game = nullptr;
static fn_void fn_run = nullptr;
static fn_system_av_info fn_get_av_info = nullptr;
static fn_void fn_reset = nullptr;

static std::string g_cuePath;
static std::string g_systemDir;
static std::string g_saveDir;
static ANativeWindow *g_window = nullptr;
static EGLDisplay g_display = EGL_NO_DISPLAY;
static EGLSurface g_surface = EGL_NO_SURFACE;
static EGLContext g_context = EGL_NO_CONTEXT;
static GLuint g_program = 0;
static GLuint g_texture = 0;
static GLint g_positionLoc = -1;
static GLint g_uvLoc = -1;
static GLint g_textureLoc = -1;

static std::thread g_thread;
static std::atomic<bool> g_running{false};
static std::atomic<bool> g_initialized{false};
static int g_pad[2][16] = {{0}};
static std::atomic<int> g_pixelFormat{RETRO_PIXEL_FORMAT_XRGB8888};

static std::vector<int16_t> g_audio;
static std::mutex g_audioMutex;

static const char *VERTEX_SHADER =
    "attribute vec2 aPosition;\n"
    "attribute vec2 aUv;\n"
    "varying vec2 vUv;\n"
    "void main() {\n"
    "  gl_Position = vec4(aPosition, 0.0, 1.0);\n"
    "  vUv = aUv;\n"
    "}\n";

static const char *FRAGMENT_SHADER =
    "precision mediump float;\n"
    "uniform sampler2D uTexture;\n"
    "varying vec2 vUv;\n"
    "void main() {\n"
    "  gl_FragColor = texture2D(uTexture, vUv);\n"
    "}\n";

static std::string jstringToStd(JNIEnv *env, jstring value) {
    if (!value) return "";
    const char *chars = env->GetStringUTFChars(value, nullptr);
    std::string result(chars ? chars : "");
    if (chars) env->ReleaseStringUTFChars(value, chars);
    return result;
}

static JNIEnv *attachThread() {
    JNIEnv *env = nullptr;
    if (g_vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) == JNI_OK) return env;
    if (g_vm->AttachCurrentThread(&env, nullptr) != JNI_OK) return nullptr;
    return env;
}

static void callError(const std::string &message) {
    LOGE("%s", message.c_str());
    JNIEnv *env = attachThread();
    if (!env || !g_host || !g_midError) return;
    jstring text = env->NewStringUTF(message.c_str());
    env->CallVoidMethod(g_host, g_midError, text);
    env->DeleteLocalRef(text);
}

static void callAudioRate(int rate) {
    JNIEnv *env = attachThread();
    if (!env || !g_host || !g_midRate) return;
    env->CallVoidMethod(g_host, g_midRate, static_cast<jint>(rate));
}

static void flushAudio(JNIEnv *env) {
    if (!env || !g_host || !g_midData) return;
    std::vector<int16_t> pcm;
    {
        std::lock_guard<std::mutex> lock(g_audioMutex);
        if (g_audio.empty()) return;
        pcm.swap(g_audio);
    }
    const jsize bytes = static_cast<jsize>(pcm.size() * sizeof(int16_t));
    jbyteArray array = env->NewByteArray(bytes);
    env->SetByteArrayRegion(array, 0, bytes, reinterpret_cast<const jbyte *>(pcm.data()));
    env->CallVoidMethod(g_host, g_midData, array, bytes);
    env->DeleteLocalRef(array);
}

static GLuint compileShader(GLenum type, const char *source) {
    GLuint shader = glCreateShader(type);
    glShaderSource(shader, 1, &source, nullptr);
    glCompileShader(shader);
    GLint status = GL_FALSE;
    glGetShaderiv(shader, GL_COMPILE_STATUS, &status);
    if (status != GL_TRUE) {
        char log[512] = {0};
        glGetShaderInfoLog(shader, sizeof(log), nullptr, log);
        LOGE("shader: %s", log);
        glDeleteShader(shader);
        return 0;
    }
    return shader;
}

static bool initGLES() {
    if (!g_window) return false;

    g_display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (g_display == EGL_NO_DISPLAY || !eglInitialize(g_display, nullptr, nullptr)) {
        LOGE("eglInitialize failed");
        return false;
    }

    const EGLint configAttribs[] = {
        EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
        EGL_SURFACE_TYPE, EGL_WINDOW_BIT,
        EGL_RED_SIZE, 8,
        EGL_GREEN_SIZE, 8,
        EGL_BLUE_SIZE, 8,
        EGL_NONE
    };
    EGLConfig config{};
    EGLint numConfigs = 0;
    if (!eglChooseConfig(g_display, configAttribs, &config, 1, &numConfigs) || numConfigs < 1) {
        LOGE("eglChooseConfig failed");
        return false;
    }

    g_surface = eglCreateWindowSurface(g_display, config, g_window, nullptr);
    if (g_surface == EGL_NO_SURFACE) {
        LOGE("eglCreateWindowSurface failed");
        return false;
    }

    const EGLint contextAttribs[] = {EGL_CONTEXT_CLIENT_VERSION, 2, EGL_NONE};
    g_context = eglCreateContext(g_display, config, EGL_NO_CONTEXT, contextAttribs);
    if (g_context == EGL_NO_CONTEXT ||
        !eglMakeCurrent(g_display, g_surface, g_surface, g_context)) {
        LOGE("eglCreateContext/eglMakeCurrent failed");
        return false;
    }

    GLuint vertex = compileShader(GL_VERTEX_SHADER, VERTEX_SHADER);
    GLuint fragment = compileShader(GL_FRAGMENT_SHADER, FRAGMENT_SHADER);
    if (!vertex || !fragment) return false;

    g_program = glCreateProgram();
    glAttachShader(g_program, vertex);
    glAttachShader(g_program, fragment);
    glLinkProgram(g_program);
    glDeleteShader(vertex);
    glDeleteShader(fragment);
    GLint linked = GL_FALSE;
    glGetProgramiv(g_program, GL_LINK_STATUS, &linked);
    if (linked != GL_TRUE) {
        char log[512] = {0};
        glGetProgramInfoLog(g_program, sizeof(log), nullptr, log);
        LOGE("program link: %s", log);
        return false;
    }

    g_positionLoc = glGetAttribLocation(g_program, "aPosition");
    g_uvLoc = glGetAttribLocation(g_program, "aUv");
    g_textureLoc = glGetUniformLocation(g_program, "uTexture");

    glGenTextures(1, &g_texture);
    glBindTexture(GL_TEXTURE_2D, g_texture);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
    return true;
}

static void videoRefresh(const void *data, unsigned width, unsigned height, size_t pitch) {
    if (!data || width == 0 || height == 0) return; // duplicated frame
    if (g_context == EGL_NO_CONTEXT) return;

    const int format = g_pixelFormat.load();
    GLenum glFormat = GL_RGBA;
    GLenum glType = GL_UNSIGNED_BYTE;
    size_t bytesPerPixel = 4;
    const void *pixels = data;

    std::vector<uint16_t> converted;
    if (format == RETRO_PIXEL_FORMAT_RGB565) {
        glFormat = GL_RGB;
        glType = GL_UNSIGNED_SHORT_5_6_5;
        bytesPerPixel = 2;
    } else if (format == RETRO_PIXEL_FORMAT_0RGB1555) {
        const uint16_t *source = static_cast<const uint16_t *>(data);
        converted.resize(static_cast<size_t>(width) * height);
        for (size_t i = 0; i < converted.size(); i++) {
            const uint16_t c = source[i];
            converted[i] = static_cast<uint16_t>(
                ((c >> 10) & 0x1F) << 11 | ((c >> 5) & 0x1F) << 6 | (c & 0x1F));
        }
        pixels = converted.data();
        glFormat = GL_RGB;
        glType = GL_UNSIGNED_SHORT_5_6_5;
        bytesPerPixel = 2;
        pitch = static_cast<size_t>(width) * 2;
    }

    glBindTexture(GL_TEXTURE_2D, g_texture);
    if (pitch == static_cast<size_t>(width) * bytesPerPixel) {
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, static_cast<GLint>(width),
                     static_cast<GLint>(height), 0, glFormat, glType, pixels);
    } else {
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, static_cast<GLint>(width),
                     static_cast<GLint>(height), 0, glFormat, glType, nullptr);
        const uint8_t *base = static_cast<const uint8_t *>(pixels);
        for (unsigned y = 0; y < height; y++) {
            glTexSubImage2D(GL_TEXTURE_2D, 0, 0, static_cast<GLint>(y),
                            static_cast<GLsizei>(width), 1, glFormat, glType,
                            base + static_cast<size_t>(y) * pitch);
        }
    }

    const int windowWidth = ANativeWindow_getWidth(g_window);
    const int windowHeight = ANativeWindow_getHeight(g_window);
    glViewport(0, 0, windowWidth, windowHeight);
    glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
    glClear(GL_COLOR_BUFFER_BIT);

    static const GLfloat vertices[] = {
        -1.0f, -1.0f, 0.0f, 1.0f,
         1.0f, -1.0f, 1.0f, 1.0f,
        -1.0f,  1.0f, 0.0f, 0.0f,
         1.0f,  1.0f, 1.0f, 0.0f,
    };

    glUseProgram(g_program);
    glEnableVertexAttribArray(static_cast<GLuint>(g_positionLoc));
    glEnableVertexAttribArray(static_cast<GLuint>(g_uvLoc));
    glVertexAttribPointer(static_cast<GLuint>(g_positionLoc), 2, GL_FLOAT, GL_FALSE,
                          4 * sizeof(GLfloat), vertices);
    glVertexAttribPointer(static_cast<GLuint>(g_uvLoc), 2, GL_FLOAT, GL_FALSE,
                          4 * sizeof(GLfloat), vertices + 2);
    glUniform1i(g_textureLoc, 0);
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, g_texture);
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    eglSwapBuffers(g_display, g_surface);
}

static void audioSample(int16_t left, int16_t right) {
    std::lock_guard<std::mutex> lock(g_audioMutex);
    g_audio.push_back(left);
    g_audio.push_back(right);
}

static size_t audioBatch(const int16_t *data, size_t frames) {
    std::lock_guard<std::mutex> lock(g_audioMutex);
    g_audio.insert(g_audio.end(), data, data + frames * 2);
    return frames;
}

static void inputPoll() {
    // Input is pushed from the UI thread through nativeKey().
}

static int16_t inputState(unsigned port, unsigned device, unsigned index, unsigned id) {
    if (device == RETRO_DEVICE_JOYPAD && port < 2) {
        if (id == RETRO_DEVICE_ID_JOYPAD_MASK) {
            int mask = 0;
            for (int i = 0; i < 16; i++) if (g_pad[port][i]) mask |= (1 << i);
            return static_cast<int16_t>(mask);
        }
        if (id < 16) return static_cast<int16_t>(g_pad[port][id] ? 1 : 0);
    }
    return 0;
}

static void logPrintf(enum retro_log_level level, const char *format, ...) {
    char buffer[1024];
    va_list args;
    va_start(args, format);
    vsnprintf(buffer, sizeof(buffer), format, args);
    va_end(args);
    __android_log_print(ANDROID_LOG_INFO, "PS1Core", "[%d] %s", static_cast<int>(level), buffer);
}

static bool environment(unsigned cmd, void *data) {
    if (!data) return false;
    switch (cmd) {
        case RETRO_ENVIRONMENT_SET_ROTATION:
        case RETRO_ENVIRONMENT_SET_INPUT_DESCRIPTORS:
        case RETRO_ENVIRONMENT_SET_VARIABLES:
        case RETRO_ENVIRONMENT_SET_PERFORMANCE_LEVEL:
        case RETRO_ENVIRONMENT_SET_CONTROLLER_INFO:
            return true;
        case RETRO_ENVIRONMENT_GET_OVERSCAN:
            *(bool *)data = false;
            return true;
        case RETRO_ENVIRONMENT_GET_CAN_DUPLICATE:
            *(bool *)data = true;
            return true;
        case RETRO_ENVIRONMENT_SET_PIXEL_FORMAT: {
            const int format = *static_cast<int *>(data);
            if (format == RETRO_PIXEL_FORMAT_XRGB8888 ||
                format == RETRO_PIXEL_FORMAT_RGB565 ||
                format == RETRO_PIXEL_FORMAT_0RGB1555) {
                g_pixelFormat.store(format);
                return true;
            }
            return false;
        }
        case RETRO_ENVIRONMENT_GET_SYSTEM_DIRECTORY:
            *reinterpret_cast<const char **>(data) = g_systemDir.c_str();
            return true;
        case RETRO_ENVIRONMENT_GET_SAVE_DIRECTORY:
            *reinterpret_cast<const char **>(data) = g_saveDir.c_str();
            return true;
        case RETRO_ENVIRONMENT_GET_USERNAME:
            *reinterpret_cast<const char **>(data) = "player";
            return true;
        case RETRO_ENVIRONMENT_GET_VARIABLE: {
            retro_variable *variable = static_cast<retro_variable *>(data);
            variable->value = "";
            return true;
        }
        case RETRO_ENVIRONMENT_GET_VARIABLE_UPDATE:
            *(bool *)data = false;
            return true;
        case RETRO_ENVIRONMENT_GET_LOG_INTERFACE:
            reinterpret_cast<retro_log_callback *>(data)->log = logPrintf;
            return true;
        case RETRO_ENVIRONMENT_SET_MESSAGE: {
            retro_message *message = static_cast<retro_message *>(data);
            if (message->msg) LOGI("core: %s", message->msg);
            return true;
        }
        case RETRO_ENVIRONMENT_SET_MESSAGE_EXT: {
            retro_message_ext *message = static_cast<retro_message_ext *>(data);
            if (message->msg) LOGI("core: %s", message->msg);
            return true;
        }
        default:
            return false;
    }
}

template <typename T>
static bool resolve(void *handle, T &target, const char *name) {
    target = reinterpret_cast<T>(dlsym(handle, name));
    if (!target) {
        LOGE("core is missing %s", name);
        return false;
    }
    return true;
}

static void emulationLoop() {
    JNIEnv *env = attachThread();
    if (!env) return;

    if (!initGLES()) {
        callError("EGL/GLES2 initialization failed");
        g_running.store(false);
        return;
    }

    retro_system_av_info av{};
    if (fn_get_av_info) fn_get_av_info(&av);
    if (av.timing.sample_rate > 0) callAudioRate(static_cast<int>(av.timing.sample_rate));
    else callAudioRate(44100);

    LOGI("core started: %ux%u @ %.2f fps", av.geometry.base_width, av.geometry.base_height, av.timing.fps);

    while (g_running.load()) {
        if (fn_run) fn_run();
        flushAudio(env);
    }

    {
        std::lock_guard<std::mutex> lock(g_audioMutex);
        g_audio.clear();
    }

    if (g_display != EGL_NO_DISPLAY) {
        eglMakeCurrent(g_display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        eglDestroyContext(g_display, g_context);
        eglDestroySurface(g_display, g_surface);
        eglTerminate(g_display);
    }
    g_display = EGL_NO_DISPLAY;
    g_surface = EGL_NO_SURFACE;
    g_context = EGL_NO_CONTEXT;

    JNIEnv *current = nullptr;
    if (g_vm->GetEnv(reinterpret_cast<void **>(&current), JNI_VERSION_1_6) == JNI_OK) {
        g_vm->DetachCurrentThread();
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_farsipsx_ps1app_NativeBridge_nativeInit(
        JNIEnv *env, jclass,
        jobject surface, jstring corePath, jstring cuePath,
        jstring systemDir, jstring saveDir, jobject host) {

    env->GetJavaVM(&g_vm);
    if (g_host) env->DeleteGlobalRef(g_host);
    g_host = env->NewGlobalRef(host);

    jclass hostClass = env->GetObjectClass(host);
    g_midRate = env->GetMethodID(hostClass, "onAudioRate", "(I)V");
    g_midData = env->GetMethodID(hostClass, "onAudioData", "([BI)V");
    g_midError = env->GetMethodID(hostClass, "onError", "(Ljava/lang/String;)V");
    if (!g_midRate || !g_midData || !g_midError) return JNI_FALSE;

    g_window = ANativeWindow_fromSurface(env, surface);
    if (!g_window) {
        callError("Surface is not available");
        return JNI_FALSE;
    }

    const std::string coreFile = jstringToStd(env, corePath);
    g_cuePath = jstringToStd(env, cuePath);
    g_systemDir = jstringToStd(env, systemDir);
    g_saveDir = jstringToStd(env, saveDir);

    g_core = dlopen(coreFile.c_str(), RTLD_LAZY | RTLD_LOCAL);
    if (!g_core) {
        callError(std::string("dlopen failed: ") + (dlerror() ? dlerror() : "unknown"));
        return JNI_FALSE;
    }

    bool ok = true;
    ok &= resolve(g_core, fn_get_system_info, "retro_get_system_info");
    ok &= resolve(g_core, fn_init, "retro_init");
    ok &= resolve(g_core, fn_deinit, "retro_deinit");
    ok &= resolve(g_core, fn_set_environment, "retro_set_environment");
    ok &= resolve(g_core, fn_set_video_refresh, "retro_set_video_refresh");
    ok &= resolve(g_core, pfn_set_audio_sample, "retro_set_audio_sample");
    ok &= resolve(g_core, pfn_set_audio_batch, "retro_set_audio_sample_batch");
    ok &= resolve(g_core, fn_set_input_poll, "retro_set_input_poll");
    ok &= resolve(g_core, fn_set_input_state, "retro_set_input_state");
    ok &= resolve(g_core, pfn_load_game, "retro_load_game");
    ok &= resolve(g_core, fn_unload_game, "retro_unload_game");
    ok &= resolve(g_core, fn_run, "retro_run");
    ok &= resolve(g_core, fn_get_av_info, "retro_get_system_av_info");
    if (!ok) return JNI_FALSE;

    fn_reset = reinterpret_cast<fn_void>(dlsym(g_core, "retro_reset"));

    retro_system_info info{};
    fn_get_system_info(&info);
    LOGI("core: %s %s", info.library_name ? info.library_name : "?",
         info.library_version ? info.library_version : "?");

    fn_set_environment(environment);
    fn_set_video_refresh(videoRefresh);
    pfn_set_audio_sample(audioSample);
    pfn_set_audio_batch(audioBatch);
    fn_set_input_poll(inputPoll);
    fn_set_input_state(inputState);

    fn_init();

    for (int port = 0; port < 2; port++)
        for (int button = 0; button < 16; button++) g_pad[port][button] = 0;

    retro_game_info game{};
    game.path = g_cuePath.c_str();
    if (!pfn_load_game(&game)) {
        callError("retro_load_game failed — check the .cue file and bin image");
        fn_deinit();
        return JNI_FALSE;
    }

    g_initialized.store(true);
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_farsipsx_ps1app_NativeBridge_nativeStart(JNIEnv *, jclass) {
    if (!g_initialized.load() || g_running.load()) return JNI_FALSE;
    g_running.store(true);
    g_thread = std::thread(emulationLoop);
    return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_farsipsx_ps1app_NativeBridge_nativeStop(JNIEnv *, jclass) {
    if (!g_running.load()) return;
    g_running.store(false);
    if (g_thread.joinable()) g_thread.join();
}

extern "C" JNIEXPORT void JNICALL
Java_com_farsipsx_ps1app_NativeBridge_nativeKey(JNIEnv *, jclass,
                                                 jint port, jint button, jboolean pressed) {
    if (port >= 0 && port < 2 && button >= 0 && button < 16) {
        g_pad[port][button] = pressed == JNI_TRUE ? 1 : 0;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_farsipsx_ps1app_NativeBridge_nativeDestroy(JNIEnv *env, jclass) {
    if (g_running.load()) {
        g_running.store(false);
        if (g_thread.joinable()) g_thread.join();
    }
    if (g_initialized.load() && fn_unload_game) fn_unload_game();
    if (g_initialized.load() && fn_deinit) fn_deinit();
    g_initialized.store(false);

    if (g_core) {
        dlclose(g_core);
        g_core = nullptr;
    }
    if (g_window) {
        ANativeWindow_release(g_window);
        g_window = nullptr;
    }
    if (g_host) {
        env->DeleteGlobalRef(g_host);
        g_host = nullptr;
    }
}
