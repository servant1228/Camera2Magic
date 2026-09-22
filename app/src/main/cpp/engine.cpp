#include "engine.h"

#include <android/log.h>
#include <android/native_window_jni.h>
#include <android/surface_texture.h>
#include <android/surface_texture_jni.h>

#include <errno.h>
#include <math.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/prctl.h>
#include <time.h>

#include "gl_abi.h"
#include "jpeg_encoder.h"

namespace camera3 {
namespace {

constexpr const char* kTag = "VCX";
constexpr float kPi = 3.14159265358979323846f;

// ---------------------------------------------------------------------------
// Shaders (GLES3). The preview program rotates/crops the media by transforming
// the target quad's vertices (u_FixMatrix) and samples the SurfaceTexture with
// its own transform (u_StMatrix). The YUV program renders a full-screen quad to
// an R8 framebuffer and writes a packed NV21 plane (Y rows, then interleaved
// V/U rows), so the CPU readback can be copied into the app's buffers almost
// verbatim. Both programs use the same attribute layout (location 0 = position,
// location 1 = texture coordinate) with their own vertex buffer.
// ---------------------------------------------------------------------------

constexpr const char* kPreviewVertexShader =
    "#version 300 es\n"
    "layout(location = 0) in vec4 a_Position;\n"
    "layout(location = 1) in vec2 a_TexCoord;\n"
    "uniform mat4 u_FixMatrix;\n"
    "uniform mat4 u_StMatrix;\n"
    "out vec2 v_TexCoord;\n"
    "void main() {\n"
    "    gl_Position = u_FixMatrix * a_Position;\n"
    "    v_TexCoord = (u_StMatrix * vec4(a_TexCoord, 0.0, 1.0)).xy;\n"
    "}\n";

constexpr const char* kYuvVertexShader =
    "#version 300 es\n"
    "layout(location = 0) in vec4 a_Position;\n"
    "layout(location = 1) in vec2 a_TexCoord;\n"
    "uniform mat4 u_FixMatrix;\n"
    "uniform mat4 u_StMatrix;\n"
    "out vec2 v_TexCoord;\n"
    "void main() {\n"
    "    gl_Position = a_Position;\n"
    "    vec2 c = (u_FixMatrix * vec4(a_TexCoord, 0.0, 1.0)).xy;\n"
    "    v_TexCoord = (u_StMatrix * vec4(c, 0.0, 1.0)).xy;\n"
    "}\n";

constexpr const char* kPreviewFragmentShader =
    "#version 300 es\n"
    "#extension GL_OES_EGL_image_external_essl3 : require\n"
    "precision mediump float;\n"
    "in vec2 v_TexCoord;\n"
    "uniform samplerExternalOES u_Texture;\n"
    "out vec4 o_FragColor;\n"
    "void main() {\n"
    "    o_FragColor = texture(u_Texture, v_TexCoord);\n"
    "}\n";

constexpr const char* kYuvFragmentShader =
    "#version 300 es\n"
    "#extension GL_OES_EGL_image_external_essl3 : require\n"
    "precision highp float;\n"
    "in vec2 v_TexCoord;\n"
    "layout(location = 0) out float o_Color;\n"
    "uniform samplerExternalOES u_Texture;\n"
    "uniform float u_Offset;\n"  // luma rows; below it come the interleaved V/U rows
    "uniform bool u_NV12;\n"
    "const vec3 COEFF_Y = vec3(0.299, 0.587, 0.114);\n"
    "const vec3 COEFF_V = vec3(0.500, -0.419, -0.081);\n"
    "const vec3 COEFF_U = vec3(-0.169, -0.331, 0.500);\n"
    "void main() {\n"
    "    vec3 rgb = texture(u_Texture, v_TexCoord).rgb;\n"
    "    if (gl_FragCoord.y < u_Offset) {\n"
    "        o_Color = dot(rgb, COEFF_Y);\n"
    "    } else {\n"
    "        bool isEven = (int(gl_FragCoord.x) & 1) == 0;\n"
    "        if (isEven != u_NV12) o_Color = dot(rgb, COEFF_V) + 0.5;\n"
    "        else o_Color = dot(rgb, COEFF_U) + 0.5;\n"
    "    }\n"
    "}\n";

// Preview quad: full clip-space rect, texture coordinates matching it.
constexpr float kPreviewQuad[4 * 4] = {
    -1.0f, -1.0f, 0.0f, 0.0f,
     1.0f, -1.0f, 1.0f, 0.0f,
    -1.0f,  1.0f, 0.0f, 1.0f,
     1.0f,  1.0f, 1.0f, 1.0f,
};

// YUV quad: the luma plane occupies the bottom 2/3 of the FBO and the chroma
// plane the top 1/3 (glReadPixels returns rows bottom-up, so the luma plane
// ends up first in the readback buffer, exactly like an NV21 buffer).
constexpr float kYuvQuad[12 * 4] = {
    -1.0f, -1.0f,       0.0f, 0.0f,
     1.0f, -1.0f,       1.0f, 0.0f,
    -1.0f, 1.0f / 3.0f, 0.0f, 1.0f,
     1.0f, -1.0f,       1.0f, 0.0f,
     1.0f, 1.0f / 3.0f, 1.0f, 1.0f,
    -1.0f, 1.0f / 3.0f, 0.0f, 1.0f,
    -1.0f, 1.0f / 3.0f, 0.0f, 0.0f,
     1.0f, 1.0f / 3.0f, 1.0f, 0.0f,
    -1.0f, 1.0f,        0.0f, 1.0f,
     1.0f, 1.0f / 3.0f, 1.0f, 0.0f,
     1.0f, 1.0f,        1.0f, 1.0f,
    -1.0f, 1.0f,        0.0f, 1.0f,
};

void logMessage(bool enabled, int priority, const char* fmt, ...) {
    if (!enabled) return;
    va_list ap;
    va_start(ap, fmt);
    __android_log_vprint(priority, kTag, fmt, ap);
    va_end(ap);
}

// Monotonic deadline `ms` milliseconds from now.
timespec deadlineIn(int ms) {
    timespec ts{};
    clock_gettime(CLOCK_MONOTONIC, &ts);
    ts.tv_sec += ms / 1000;
    ts.tv_nsec += (long)(ms % 1000) * 1000000L;
    if (ts.tv_nsec >= 1000000000L) {
        ts.tv_sec += 1;
        ts.tv_nsec -= 1000000000L;
    }
    return ts;
}

// Same app workarounds the closed-source engine used: these apps pre-rotate
// their camera pipeline (or embed rotation in the stream), so mirroring the
// sensor orientation again would double-rotate.
int calculateRotation(int api, int sensorOri, const char* processName, bool* mirrorOut,
                      bool facingFront) {
    int rotation = api > 1 ? sensorOri : 0;
    if (processName) {
        if (strstr(processName, "com.whatsapp")) rotation = 0;
        if (strstr(processName, "com.tencent.mm:a")) rotation = 0;
        if (api == 2) {
            if (strstr(processName, "org.telegram.messenger")) rotation = 0;
            if (strstr(processName, "com.instagram.android")) rotation = 0;
        }
    }
    if (mirrorOut) *mirrorOut = (api == 1) && facingFront;
    return rotation;
}

// Texture-coordinate transform used for preview targets: rotate by the camera
// rotation and scale for center-crop ("fill") against the target aspect.
void buildPreviewFixMatrix(float out[16], int targetW, int targetH, int api, bool facingFront,
                           int sensorOri, int displayOri, const char* processName, int frameW,
                           int frameH) {
    bool mirror = false;
    int rotation = calculateRotation(api, sensorOri, processName, &mirror, facingFront);

    // Camera1: portrait targets with a 90-degree display orientation swap the
    // aspect ratio the fit is computed against (mirrors the HAL's behavior).
    int mode = 0;
    if (targetW < targetH && api == 1 && (displayOri % 180) == 90) {
        mode = facingFront ? 1 : 2;
    }

    float frameAspect = (float)frameW / (float)frameH;
    float targetAspect = mode == 0 ? (float)targetH / (float)targetW
                                   : (float)targetW / (float)targetH;
    float sx = 1.0f;
    float sy = 1.0f;
    if (frameAspect > targetAspect) {
        sx = frameAspect / targetAspect;
    } else {
        sy = targetAspect / frameAspect;
    }

    float rad = (float)rotation * kPi / 180.0f;
    float sn = sinf(rad);
    float cs = cosf(rad);
    float ms = mirror ? -1.0f : 1.0f;

    memset(out, 0, 16 * sizeof(float));
    out[0] = ms * sx * sn;
    out[1] = ms * sx * cs;
    out[4] = -sy * cs;
    out[5] = sy * sn;
    out[10] = 1.0f;
    out[15] = 1.0f;
}

// Media (photos / videos) is always display-upright, while phone camera sensors
// are landscape (sensorOri 90/270). Apps consume preview/YUV frames in the
// sensor layout and apply their own display transform, so portrait media must
// be rotated into the sensor frame before it is drawn — otherwise every
// portrait photo/video comes out sideways and users have to work around it with
// the manual-rotation setting.
//
// `frameRot` is the producer's unapplied rotation in degrees (ExoPlayer's
// unappliedRotationDegrees, or a photo's EXIF orientation) and is folded in so
// media whose pixels are pre-rotated ends up in the same place.
int alignSensorOrientation(int sensorOri, int frameW, int frameH, int frameRot) {
    int sensor = ((sensorOri % 360) + 360) % 360;
    int rotation = ((frameRot % 360) + 360) % 360;
    bool rotated90 = (rotation % 180) == 90;
    // Display-upright media aspect after the producer's rotation is applied.
    bool mediaPortrait = rotated90 ? (frameW > frameH) : (frameH > frameW);
    if ((sensor % 180) == 90) {
        // Landscape sensor (the common case): a portrait frame has to be
        // rotated a quarter turn to match the sensor layout.
        if (mediaPortrait) sensor = (sensor + 270) % 360;
    } else if (!mediaPortrait) {
        // Portrait sensor (rare): the mirrored adjustment for landscape media.
        sensor = (sensor + 90) % 360;
    }
    return (sensor + rotation) % 360;
}

// Texture-coordinate transform used for the YUV readback. The frame is stored
// in sensor orientation (360 - sensorOri) and letterboxed inside the requested
// output size so the app's analysis buffer sees the same field of view.
void buildYuvFixMatrix(float out[16], int frameW, int frameH, int sensorOri, int outW, int outH) {
    int rotation = (360 - sensorOri) % 360;
    if (rotation < 0) rotation += 360;
    bool swap = (rotation % 180) == 90;

    float a = swap ? (float)frameH / (float)frameW : (float)frameW / (float)frameH;
    float b = (float)outW / (float)outH;
    float sx = 1.0f;
    float sy = 1.0f;
    if (a <= b) {
        sx = a / b;
    } else {
        sy = b / a;
    }

    float rad = (float)rotation * kPi / 180.0f;
    float sn = sinf(rad);
    float cs = cosf(rad);

    float m0 = sx * cs;
    float m1 = sx * sn;
    float m4 = sy * sn;
    float m5 = -sy * cs;

    memset(out, 0, 16 * sizeof(float));
    out[0] = m0;
    out[1] = m1;
    out[4] = m4;
    out[5] = m5;
    out[12] = 0.5f - 0.5f * (m0 + m4);
    out[13] = 0.5f - 0.5f * (m1 + m5);
    out[10] = 1.0f;
    out[15] = 1.0f;
}

GLuint compileShader(bool log, GLenum type, const char* source) {
    GLuint shader = glCreateShader(type);
    if (!shader) return 0;
    glShaderSource(shader, 1, &source, nullptr);
    glCompileShader(shader);
    GLint ok = 0;
    glGetShaderiv(shader, GL_COMPILE_STATUS, &ok);
    if (!ok) {
        logMessage(log, ANDROID_LOG_ERROR, "[Camera3] shader compile failed (type=%u)", type);
        glDeleteShader(shader);
        return 0;
    }
    return shader;
}

GLuint buildProgram(bool log, const char* vertexSource, const char* fragmentSource) {
    GLuint vs = compileShader(log, GL_VERTEX_SHADER, vertexSource);
    if (!vs) return 0;
    GLuint fs = compileShader(log, GL_FRAGMENT_SHADER, fragmentSource);
    if (!fs) {
        glDeleteShader(vs);
        return 0;
    }
    GLuint program = glCreateProgram();
    glAttachShader(program, vs);
    glAttachShader(program, fs);
    glLinkProgram(program);
    glDeleteShader(vs);
    glDeleteShader(fs);
    GLint ok = 0;
    glGetProgramiv(program, GL_LINK_STATUS, &ok);
    if (!ok) {
        logMessage(log, ANDROID_LOG_ERROR, "[Camera3] program link failed");
        glDeleteProgram(program);
        return 0;
    }
    return program;
}

void makeVao(GLuint* vao, GLuint* vbo, const float* data, size_t bytes) {
    glGenVertexArrays(1, vao);
    glGenBuffers(1, vbo);
    glBindVertexArray(*vao);
    glBindBuffer(GL_ARRAY_BUFFER, *vbo);
    glBufferData(GL_ARRAY_BUFFER, (GLsizeiptr)bytes, data, GL_STATIC_DRAW);
    glEnableVertexAttribArray(0);
    glVertexAttribPointer(0, 2, GL_FLOAT, GL_FALSE, 4 * sizeof(float), (const void*)0);
    glEnableVertexAttribArray(1);
    glVertexAttribPointer(1, 2, GL_FLOAT, GL_FALSE, 4 * sizeof(float),
                          (const void*)(2 * sizeof(float)));
    glBindVertexArray(0);
    glBindBuffer(GL_ARRAY_BUFFER, 0);
}

}  // namespace

// ---------------------------------------------------------------------------
// Singleton
// ---------------------------------------------------------------------------

// Static storage: function-local statics would pull in the C++ runtime's
// thread-safe-static guards (libc++abi). The constructor only initialises
// pthread primitives, so plain dynamic initialisation is enough.
Engine gInstance;

Engine& Engine::get() {
    return gInstance;
}

Engine::Engine() {
    pthread_mutexattr_t mutexAttr;
    pthread_mutexattr_init(&mutexAttr);
    pthread_mutex_init(&mutex_, &mutexAttr);
    pthread_mutexattr_destroy(&mutexAttr);

    pthread_condattr_t condAttr;
    pthread_condattr_init(&condAttr);
    pthread_condattr_setclock(&condAttr, CLOCK_MONOTONIC);
    pthread_cond_init(&cond_, &condAttr);
    pthread_condattr_destroy(&condAttr);
}

void Engine::configure() {
    pthread_mutex_lock(&mutex_);
    if (running_) {
        pthread_mutex_unlock(&mutex_);
        return;
    }
    running_ = true;
    pthread_t thread;
    if (pthread_create(&thread, nullptr, &Engine::threadTrampoline, this) != 0) {
        running_ = false;
        pthread_mutex_unlock(&mutex_);
        return;
    }
    pthread_detach(thread);
    pthread_mutex_unlock(&mutex_);
}

void* Engine::threadTrampoline(void* arg) {
#ifdef PR_SET_NAME
    prctl(PR_SET_NAME, "camera3-render", 0, 0, 0);
#endif
    static_cast<Engine*>(arg)->renderLoop();
    return nullptr;
}

// ---------------------------------------------------------------------------
// Configuration entry points
// ---------------------------------------------------------------------------

void Engine::setLogEnabled(bool enabled) {
    pthread_mutex_lock(&mutex_);
    logEnabled_ = enabled;
    reportInitLocked();
    pthread_mutex_unlock(&mutex_);
}

void Engine::reportInitLocked() {
    if (!logEnabled_ || initReported_ || initState_ == 0) return;
    initReported_ = true;
    if (initState_ > 0) {
        logMessage(true, ANDROID_LOG_INFO, "[Camera3] render thread ready (OES tex %u)",
                   oesTexture_);
    } else {
        logMessage(true, ANDROID_LOG_ERROR, "[Camera3] EGL/GL init failed");
    }
}

void Engine::updateCameraBaseData(int api, bool facingFront, int sensorOri, int displayOri,
                                  const char* processName) {
    pthread_mutex_lock(&mutex_);
    api_ = api;
    facingFront_ = facingFront;
    sensorOri_ = sensorOri;
    displayOri_ = displayOri;
    if (processName) {
        strncpy(processName_, processName, kMaxProcessName - 1);
        processName_[kMaxProcessName - 1] = '\0';
    } else {
        processName_[0] = '\0';
    }
    bool log = logEnabled_;
    pthread_mutex_unlock(&mutex_);
    logMessage(log, ANDROID_LOG_INFO, "[Camera3] api=%d front=%d sensor=%d display=%d",
               api, facingFront ? 1 : 0, sensorOri, displayOri);
}

void Engine::updateManualRotation(int rotation) {
    pthread_mutex_lock(&mutex_);
    manualRotation_ = rotation;
    manualRotationSet_ = true;
    pthread_mutex_unlock(&mutex_);
    // The closed-source engine stored this value but never read it back; manual
    // rotation actually reaches the renderer through updateCameraBaseData()
    // (SourceManager folds the user angle into sensorOri/displayOri). Kept for
    // WireCompat with WebRTCHooker, which still calls it for its auto-rotation.
}

void Engine::updateFrameInfo(int width, int height, int rotation) {
    pthread_mutex_lock(&mutex_);
    frameW_ = width;
    frameH_ = height;
    frameRot_ = rotation;
    pthread_mutex_unlock(&mutex_);
}

void Engine::updateAlgorithmSize(int width, int height) {
    pthread_mutex_lock(&mutex_);
    algorithmW_ = width;
    algorithmH_ = height;
    // A Camera2 analysis surface is the authoritative YUV size: keep producing
    // fresh frames for it even without an explicit request.
    if (width > 0 && height > 0) {
        autoYuvW_ = width;
        autoYuvH_ = height;
    }
    pthread_cond_signal(&cond_);
    pthread_mutex_unlock(&mutex_);
}

int Engine::createOESTexture() {
    pthread_mutex_lock(&mutex_);
    timespec deadline = deadlineIn(1000);
    while (!initialized_ && running_) {
        if (pthread_cond_timedwait(&cond_, &mutex_, &deadline) == ETIMEDOUT) break;
    }
    int texture = (int)oesTexture_;
    pthread_mutex_unlock(&mutex_);
    return texture;
}

void Engine::setSurfaceTexture(JNIEnv* env, jobject surfaceTexture) {
    ASurfaceTexture* next =
        surfaceTexture ? ASurfaceTexture_fromSurfaceTexture(env, surfaceTexture) : nullptr;
    pthread_mutex_lock(&mutex_);
    ASurfaceTexture* stale = pendingReleaseTexture_;
    pendingReleaseTexture_ = surfaceTexture_;
    surfaceTexture_ = next;
    frameAvailable_ = false;
    haveStMatrix_ = false;
    pthread_mutex_unlock(&mutex_);
    // Released here only if the render thread has not picked the previous
    // pending handle up yet; either way the handle the renderer may be using is
    // never released underneath it.
    if (stale) ASurfaceTexture_release(stale);
}

void Engine::getSurfaceInfo(JNIEnv* env, jobject surface, int out[3]) {
    out[0] = out[1] = out[2] = 0;
    ANativeWindow* window = surface ? ANativeWindow_fromSurface(env, surface) : nullptr;
    if (window) {
        out[0] = ANativeWindow_getWidth(window);
        out[1] = ANativeWindow_getHeight(window);
        out[2] = ANativeWindow_getFormat(window);
        ANativeWindow_release(window);
    }
}

void Engine::notifyFrameAvailable() {
    pthread_mutex_lock(&mutex_);
    if (running_) {
        frameAvailable_ = true;
        pthread_cond_signal(&cond_);
    }
    pthread_mutex_unlock(&mutex_);
}

int Engine::findTarget(ANativeWindow* window) const {
    for (int i = 0; i < kMaxTargets; ++i) {
        if (targets_[i].window == window) return i;
    }
    return -1;
}

void Engine::addRenderTarget(JNIEnv* env, jobject surface, int vW, int vH, int pW, int pH) {
    ANativeWindow* window = surface ? ANativeWindow_fromSurface(env, surface) : nullptr;
    if (!window) return;
    bool keepRef = false;
    pthread_mutex_lock(&mutex_);
    int index = findTarget(window);
    if (index < 0) {
        for (int i = 0; i < kMaxTargets; ++i) {
            if (targets_[i].window == nullptr) {
                index = i;
                if (i >= targetCount_) targetCount_ = i + 1;
                break;
            }
        }
    }
    if (index < 0) {
        pthread_mutex_unlock(&mutex_);
        ANativeWindow_release(window);
        return;
    }
    Target& target = targets_[index];
    if (target.window == nullptr) {
        target.window = window;  // ownership of the fromSurface reference moves here
        target.egl = nullptr;
        keepRef = true;
    }
    target.active = true;
    target.vW = vW;
    target.vH = vH;
    target.pW = pW;
    target.pH = pH;
    if (autoYuvW_ == 0 && algorithmW_ == 0 && vW > 0 && vH > 0) {
        autoYuvW_ = vW;
        autoYuvH_ = vH;
    }
    pthread_cond_signal(&cond_);
    pthread_mutex_unlock(&mutex_);
    if (!keepRef) ANativeWindow_release(window);
}

void Engine::addRenderTarget(JNIEnv* env, jobject surface) {
    addRenderTarget(env, surface, 0, 0, 0, 0);
}

void Engine::removeRenderTarget(JNIEnv* env, jobject surface) {
    ANativeWindow* window = surface ? ANativeWindow_fromSurface(env, surface) : nullptr;
    if (!window) return;
    pthread_mutex_lock(&mutex_);
    int index = findTarget(window);
    if (index >= 0) {
        targets_[index].active = false;
        cleanupPending_ = true;
        pthread_cond_signal(&cond_);
    }
    pthread_mutex_unlock(&mutex_);
    ANativeWindow_release(window);
}

void Engine::clearTargets() {
    pthread_mutex_lock(&mutex_);
    for (int i = 0; i < kMaxTargets; ++i) {
        if (targets_[i].window) targets_[i].active = false;
    }
    cleanupPending_ = true;
    pthread_cond_signal(&cond_);
    pthread_mutex_unlock(&mutex_);
}

// ---------------------------------------------------------------------------
// YUV consumers
// ---------------------------------------------------------------------------

bool Engine::ensureYuvLocked(int width, int height, uint8_t** outData) {
    *outData = nullptr;
    if (!running_ || !initialized_) return false;
    timespec deadline = deadlineIn(kYuvWaitMs);
    for (;;) {
        if (yuvReady_ && yuvBuffer_ && yuvBufferW_ == width && yuvBufferH_ == height && !bufBusy_) {
            *outData = yuvBuffer_;
            return true;
        }
        requestYuvW_ = width;
        requestYuvH_ = height;
        ++yuvDemand_;
        pthread_cond_signal(&cond_);
        int rc = pthread_cond_timedwait(&cond_, &mutex_, &deadline);
        if (rc == ETIMEDOUT || !running_) return false;
    }
}

void Engine::overwriteYuvBuffer(JNIEnv* env, jbyteArray dst, int width, int height) {
    if (!dst || width <= 0 || height <= 0) return;
    const size_t want = (size_t)width * height * 3 / 2;
    const size_t len = (size_t)env->GetArrayLength(dst);
    jbyte* bytes = env->GetByteArrayElements(dst, nullptr);
    if (!bytes) return;

    pthread_mutex_lock(&mutex_);
    uint8_t* src = nullptr;
    bool ok = ensureYuvLocked(width, height, &src);
    if (ok) {
        size_t n = len < want ? len : want;
        memcpy(bytes, src, n);
        if (n < len) memset(bytes + n, 0, len - n);
    } else {
        // No frame yet (or size change timed out): hand the app a neutral
        // black NV21 frame instead of stale/garbage pixels.
        size_t n = len < want ? len : want;
        size_t yPlane = (size_t)width * height;
        if (yPlane > n) yPlane = n;
        memset(bytes, 0, yPlane);
        if (n > yPlane) memset(bytes + yPlane, 128, n - yPlane);
        if (n < len) memset(bytes + n, 0, len - n);
    }
    pthread_mutex_unlock(&mutex_);
    env->ReleaseByteArrayElements(dst, bytes, 0);
}

void Engine::overwriteYuvPlanes(JNIEnv* env, jobject yBuffer, int yRowStride, int yPixelStride,
                                jobject uBuffer, int uRowStride, int uPixelStride,
                                jobject vBuffer, int vRowStride, int vPixelStride) {
    void* yData = yBuffer ? env->GetDirectBufferAddress(yBuffer) : nullptr;
    void* uData = uBuffer ? env->GetDirectBufferAddress(uBuffer) : nullptr;
    void* vData = vBuffer ? env->GetDirectBufferAddress(vBuffer) : nullptr;
    size_t yCap = yBuffer ? (size_t)env->GetDirectBufferCapacity(yBuffer) : 0;
    size_t uCap = uBuffer ? (size_t)env->GetDirectBufferCapacity(uBuffer) : 0;
    size_t vCap = vBuffer ? (size_t)env->GetDirectBufferCapacity(vBuffer) : 0;
    if (!yData || !uData || !vData) return;

    pthread_mutex_lock(&mutex_);
    int width = algorithmW_;
    int height = algorithmH_;
    uint8_t* src = nullptr;
    bool ok = width > 0 && height > 0 && ensureYuvLocked(width, height, &src);
    if (ok) {
        // Y plane: one byte per pixel (pixelStride is normally 1).
        for (int row = 0; row < height; ++row) {
            uint8_t* dst = (uint8_t*)yData + (size_t)row * yRowStride;
            const uint8_t* s = src + (size_t)row * width;
            for (int col = 0; col < width; ++col) {
                size_t off = (size_t)row * yRowStride + (size_t)col * yPixelStride;
                if (off >= yCap) break;
                dst[(size_t)col * yPixelStride] = s[col];
            }
        }
        // Interleaved V/U rows: V at even source offsets, U at odd (NV21).
        int chromaRows = (height + 1) / 2;
        int chromaCols = (width + 1) / 2;
        for (int row = 0; row < chromaRows; ++row) {
            const uint8_t* s = src + (size_t)(height + row) * width;
            uint8_t* uRow = (uint8_t*)uData + (size_t)row * uRowStride;
            uint8_t* vRow = (uint8_t*)vData + (size_t)row * vRowStride;
            for (int col = 0; col < chromaCols; ++col) {
                size_t uOff = (size_t)row * uRowStride + (size_t)col * uPixelStride;
                size_t vOff = (size_t)row * vRowStride + (size_t)col * vPixelStride;
                if (uOff < uCap) uRow[(size_t)col * uPixelStride] = s[col * 2 + 1];
                if (vOff < vCap) vRow[(size_t)col * vPixelStride] = s[col * 2];
            }
        }
    } else {
        // Neutral frame on timeout: Y = 0, chroma = 128.
        for (int row = 0; row < height; ++row) {
            for (int col = 0; col < width; ++col) {
                size_t off = (size_t)row * yRowStride + (size_t)col * yPixelStride;
                if (off < yCap) ((uint8_t*)yData)[off] = 0;
            }
        }
        int chromaRows = (height + 1) / 2;
        int chromaCols = (width + 1) / 2;
        for (int row = 0; row < chromaRows; ++row) {
            for (int col = 0; col < chromaCols; ++col) {
                size_t uOff = (size_t)row * uRowStride + (size_t)col * uPixelStride;
                size_t vOff = (size_t)row * vRowStride + (size_t)col * vPixelStride;
                if (uOff < uCap) ((uint8_t*)uData)[uOff] = 128;
                if (vOff < vCap) ((uint8_t*)vData)[vOff] = 128;
            }
        }
    }
    pthread_mutex_unlock(&mutex_);
}

int Engine::pictureWidthLocked() const {
    for (int i = 0; i < kMaxTargets; ++i) {
        if (targets_[i].window && targets_[i].pW > 0) return targets_[i].pW;
    }
    if (algorithmW_ > 0) return algorithmW_;
    if (autoYuvW_ > 0) return autoYuvW_;
    return frameW_;
}

int Engine::pictureHeightLocked() const {
    for (int i = 0; i < kMaxTargets; ++i) {
        if (targets_[i].window && targets_[i].pH > 0) return targets_[i].pH;
    }
    if (algorithmH_ > 0) return algorithmH_;
    if (autoYuvH_ > 0) return autoYuvH_;
    return frameH_;
}

jbyteArray Engine::overwriteJpegBytes(JNIEnv* env, int quality) {
    int orientation = 1;
    uint8_t* snapshot = nullptr;
    int width = 0;
    int height = 0;

    pthread_mutex_lock(&mutex_);
    width = pictureWidthLocked();
    height = pictureHeightLocked();
    int rotation = (360 - sensorOri_) % 360;
    if (rotation < 0) rotation += 360;
    orientation = rotation == 270 ? 6 : rotation == 90 ? 8 : rotation == 180 ? 3 : 1;

    uint8_t* src = nullptr;
    if (width > 0 && height > 0 && ensureYuvLocked(width, height, &src)) {
        const size_t bytes = (size_t)width * height * 3 / 2;
        snapshot = (uint8_t*)malloc(bytes);
        if (snapshot) memcpy(snapshot, src, bytes);
    }
    pthread_mutex_unlock(&mutex_);

    if (!snapshot) return nullptr;

    size_t outSize = 0;
    uint8_t* jpeg = jpeg_encode_nv21(snapshot, width, height, orientation, quality, &outSize);
    free(snapshot);
    if (!jpeg) return nullptr;

    jbyteArray result = env->NewByteArray((jsize)outSize);
    if (result) {
        env->SetByteArrayRegion(result, 0, (jsize)outSize, (const jbyte*)jpeg);
    }
    free(jpeg);
    return result;
}

// ---------------------------------------------------------------------------
// Render thread
// ---------------------------------------------------------------------------

bool Engine::initEGL() {
    display_ = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (display_ == EGL_NO_DISPLAY) return false;
    if (!eglInitialize(display_, nullptr, nullptr)) return false;

    const EGLint configAttribs[] = {
        EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT,
        EGL_SURFACE_TYPE, EGL_WINDOW_BIT | EGL_PBUFFER_BIT,
        EGL_RED_SIZE, 8,
        EGL_GREEN_SIZE, 8,
        EGL_BLUE_SIZE, 8,
        EGL_ALPHA_SIZE, 8,
        EGL_NONE,
    };
    EGLint numConfigs = 0;
    if (!eglChooseConfig(display_, configAttribs, &config_, 1, &numConfigs) || numConfigs < 1) {
        return false;
    }

    const EGLint contextAttribs[] = {EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE};
    context_ = eglCreateContext(display_, config_, EGL_NO_CONTEXT, contextAttribs);
    if (context_ == EGL_NO_CONTEXT) return false;

    const EGLint pbufferAttribs[] = {EGL_WIDTH, 1, EGL_HEIGHT, 1, EGL_NONE};
    pbuffer_ = eglCreatePbufferSurface(display_, config_, pbufferAttribs);
    if (pbuffer_ == EGL_NO_SURFACE) return false;

    return eglMakeCurrent(display_, pbuffer_, pbuffer_, context_) == EGL_TRUE;
}

bool Engine::initGL() {
    previewProgram_ = buildProgram(logEnabled_, kPreviewVertexShader, kPreviewFragmentShader);
    if (!previewProgram_) return false;
    previewTexLoc_ = glGetUniformLocation(previewProgram_, "u_Texture");
    previewFixLoc_ = glGetUniformLocation(previewProgram_, "u_FixMatrix");
    previewStLoc_ = glGetUniformLocation(previewProgram_, "u_StMatrix");

    yuvProgram_ = buildProgram(logEnabled_, kYuvVertexShader, kYuvFragmentShader);
    if (!yuvProgram_) return false;
    yuvTexLoc_ = glGetUniformLocation(yuvProgram_, "u_Texture");
    yuvFixLoc_ = glGetUniformLocation(yuvProgram_, "u_FixMatrix");
    yuvStLoc_ = glGetUniformLocation(yuvProgram_, "u_StMatrix");
    yuvOffsetLoc_ = glGetUniformLocation(yuvProgram_, "u_Offset");
    yuvNv12Loc_ = glGetUniformLocation(yuvProgram_, "u_NV12");

    makeVao(&previewVao_, &previewVbo_, kPreviewQuad, sizeof(kPreviewQuad));
    makeVao(&yuvVao_, &yuvVbo_, kYuvQuad, sizeof(kYuvQuad));

    glGenTextures(1, &oesTexture_);
    glBindTexture(GL_TEXTURE_EXTERNAL_OES, oesTexture_);
    glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glBindTexture(GL_TEXTURE_EXTERNAL_OES, 0);
    return true;
}

void Engine::ensureTargetSurfaces() {
    for (int i = 0; i < kMaxTargets; ++i) {
        ANativeWindow* window = nullptr;
        pthread_mutex_lock(&mutex_);
        if (targets_[i].active && targets_[i].window && !targets_[i].egl) {
            window = targets_[i].window;
        }
        pthread_mutex_unlock(&mutex_);
        if (!window) continue;

        EGLSurface surface = eglCreateWindowSurface(display_, config_, window, nullptr);
        pthread_mutex_lock(&mutex_);
        if (surface && targets_[i].window == window && targets_[i].active && !targets_[i].egl) {
            targets_[i].egl = surface;
        } else if (surface) {
            pthread_mutex_unlock(&mutex_);
            eglDestroySurface(display_, surface);
            continue;
        }
        pthread_mutex_unlock(&mutex_);
    }
}

void Engine::cleanupTargets() {
    for (int i = 0; i < kMaxTargets; ++i) {
        ANativeWindow* window = nullptr;
        EGLSurface surface = nullptr;
        pthread_mutex_lock(&mutex_);
        if (!targets_[i].active && targets_[i].window) {
            window = targets_[i].window;
            surface = targets_[i].egl;
            targets_[i].window = nullptr;
            targets_[i].egl = nullptr;
        }
        pthread_mutex_unlock(&mutex_);
        if (surface) {
            eglMakeCurrent(display_, pbuffer_, pbuffer_, context_);
            eglDestroySurface(display_, surface);
        }
        if (window) ANativeWindow_release(window);
    }
    pthread_mutex_lock(&mutex_);
    while (targetCount_ > 0 && targets_[targetCount_ - 1].window == nullptr) {
        --targetCount_;
    }
    pthread_mutex_unlock(&mutex_);
}

void Engine::renderPreviews(const Target* targets, int count, int api, bool facingFront,
                            int sensorOri, int displayOri, const char* processName, int frameW,
                            int frameH, const float stMatrix[16]) {
    for (int i = 0; i < count; ++i) {
        const Target& target = targets[i];
        if (!target.egl || !target.window) continue;
        if (eglMakeCurrent(display_, target.egl, target.egl, context_) != EGL_TRUE) {
            logMessage(logEnabled_, ANDROID_LOG_ERROR, "[Camera3] eglMakeCurrent failed: 0x%x",
                       eglGetError());
            continue;
        }
        int width = ANativeWindow_getWidth(target.window);
        int height = ANativeWindow_getHeight(target.window);
        if (width <= 0 || height <= 0) continue;

        float fix[16];
        buildPreviewFixMatrix(fix, width, height, api, facingFront, sensorOri, displayOri,
                              processName, frameW, frameH);

        glViewport(0, 0, width, height);
        glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
        glClear(GL_COLOR_BUFFER_BIT);
        glUseProgram(previewProgram_);
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_EXTERNAL_OES, oesTexture_);
        glUniform1i(previewTexLoc_, 0);
        glUniformMatrix4fv(previewStLoc_, 1, GL_FALSE, stMatrix);
        glUniformMatrix4fv(previewFixLoc_, 1, GL_FALSE, fix);
        glBindVertexArray(previewVao_);
        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
        glBindVertexArray(0);
        glBindTexture(GL_TEXTURE_EXTERNAL_OES, 0);
        glUseProgram(0);
        eglSwapBuffers(display_, target.egl);
    }
    eglMakeCurrent(display_, pbuffer_, pbuffer_, context_);
}

void Engine::renderYuv(int width, int height, int sensorOri, int frameW, int frameH,
                       const float stMatrix[16]) {
    const int totalHeight = height + (height + 1) / 2;
    if (yuvFboW_ != width || yuvFboH_ != totalHeight) {
        if (yuvFboTexture_) glDeleteTextures(1, &yuvFboTexture_);
        glGenTextures(1, &yuvFboTexture_);
        glBindTexture(GL_TEXTURE_2D, yuvFboTexture_);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_R8, width, totalHeight, 0, GL_RED, GL_UNSIGNED_BYTE,
                     nullptr);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        if (!yuvFbo_) glGenFramebuffers(1, &yuvFbo_);
        glBindFramebuffer(GL_FRAMEBUFFER, yuvFbo_);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, yuvFboTexture_,
                               0);
        if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) {
            logMessage(logEnabled_, ANDROID_LOG_ERROR, "[Camera3] YUV framebuffer incomplete");
            glBindFramebuffer(GL_FRAMEBUFFER, 0);
            glDeleteFramebuffers(1, &yuvFbo_);
            yuvFbo_ = 0;
            glDeleteTextures(1, &yuvFboTexture_);
            yuvFboTexture_ = 0;
            yuvFboW_ = yuvFboH_ = 0;
            return;
        }
        yuvFboW_ = width;
        yuvFboH_ = totalHeight;
    }

    float fix[16];
    buildYuvFixMatrix(fix, frameW, frameH, sensorOri, width, height);

    glDisable(GL_BLEND);
    glDisable(GL_DEPTH_TEST);
    glDisable(GL_CULL_FACE);
    glBindFramebuffer(GL_FRAMEBUFFER, yuvFbo_);
    glUseProgram(yuvProgram_);
    glBindVertexArray(yuvVao_);
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_EXTERNAL_OES, oesTexture_);
    glUniform1i(yuvTexLoc_, 0);
    glUniformMatrix4fv(yuvFixLoc_, 1, GL_FALSE, fix);
    glUniformMatrix4fv(yuvStLoc_, 1, GL_FALSE, stMatrix);
    glUniform1f(yuvOffsetLoc_, (float)height);
    glUniform1f(yuvNv12Loc_, 0.0f);  // NV21: V first, then U
    glViewport(0, 0, width, totalHeight);
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 12);
    glBindVertexArray(0);
    glBindTexture(GL_TEXTURE_EXTERNAL_OES, 0);
    glUseProgram(0);

    // Hand the CPU buffer to the readback; consumers block on bufBusy_ while we
    // fill it, so glReadPixels does not have to run under the lock.
    pthread_mutex_lock(&mutex_);
    const size_t need = (size_t)width * totalHeight;
    if (yuvBufferW_ != width || yuvBufferH_ != height || !yuvBuffer_) {
        free(yuvBuffer_);
        yuvBuffer_ = (uint8_t*)malloc(need);
        yuvBufferW_ = width;
        yuvBufferH_ = height;
        yuvReady_ = false;
    }
    uint8_t* dst = yuvBuffer_;
    bool haveBuffer = dst != nullptr;
    if (haveBuffer) bufBusy_ = true;
    pthread_mutex_unlock(&mutex_);
    if (!haveBuffer) {
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        return;
    }

    glBindFramebuffer(GL_FRAMEBUFFER, yuvFbo_);
    glPixelStorei(GL_PACK_ALIGNMENT, 1);
    glPixelStorei(GL_PACK_ROW_LENGTH, 0);
    glReadPixels(0, 0, width, totalHeight, GL_RED, GL_UNSIGNED_BYTE, dst);
    glBindFramebuffer(GL_FRAMEBUFFER, 0);

    pthread_mutex_lock(&mutex_);
    bufBusy_ = false;
    yuvReady_ = true;
    yuvPublished_ = yuvDemand_;  // satisfy every waiter that is still pending
    pthread_cond_broadcast(&cond_);
    pthread_mutex_unlock(&mutex_);
}

void Engine::renderLoop() {
    if (!initEGL() || !initGL()) {
        pthread_mutex_lock(&mutex_);
        running_ = false;
        initialized_ = false;
        initState_ = -1;
        reportInitLocked();
        pthread_cond_broadcast(&cond_);
        pthread_mutex_unlock(&mutex_);
        return;
    }
    pthread_mutex_lock(&mutex_);
    initialized_ = true;
    initState_ = 1;
    reportInitLocked();
    pthread_cond_broadcast(&cond_);
    pthread_mutex_unlock(&mutex_);

    for (;;) {
        Target snapshots[kMaxTargets];
        int count = 0;
        ASurfaceTexture* surfaceTexture = nullptr;
        ASurfaceTexture* staleTexture = nullptr;
        bool newFrame = false;
        bool cleanup = false;
        bool demandPending = false;
        bool automaticYuv = false;
        int frameW = 0, frameH = 0, frameRot = 0;
        int api = 1, sensorOri = 0, displayOri = 0;
        bool facingFront = false;
        int yuvW = 0, yuvH = 0;
        char processName[kMaxProcessName];

        pthread_mutex_lock(&mutex_);
        while (running_ && !frameAvailable_ && yuvDemand_ == yuvPublished_ &&
               !cleanupPending_) {
            pthread_cond_wait(&cond_, &mutex_);
        }
        if (!running_) {
            pthread_mutex_unlock(&mutex_);
            break;
        }
        newFrame = frameAvailable_;
        frameAvailable_ = false;
        cleanup = cleanupPending_;
        cleanupPending_ = false;
        staleTexture = pendingReleaseTexture_;
        pendingReleaseTexture_ = nullptr;
        surfaceTexture = surfaceTexture_;
        frameW = frameW_;
        frameH = frameH_;
        frameRot = frameRot_;
        api = api_;
        sensorOri = sensorOri_;
        displayOri = displayOri_;
        facingFront = facingFront_;
        demandPending = yuvDemand_ != yuvPublished_;
        automaticYuv = autoYuvW_ > 0 && autoYuvH_ > 0;
        if (requestYuvW_ > 0) {
            yuvW = requestYuvW_;
            yuvH = requestYuvH_;
        } else {
            yuvW = autoYuvW_;
            yuvH = autoYuvH_;
        }
        memcpy(processName, processName_, sizeof(processName));
        pthread_mutex_unlock(&mutex_);

        if (staleTexture) ASurfaceTexture_release(staleTexture);
        // Destroy detached targets before taking the render snapshot: an entry
        // can only be freed by this thread, so anything present in the snapshot
        // below is guaranteed to stay alive until this iteration finishes.
        if (cleanup) cleanupTargets();
        if (!newFrame && !demandPending && !automaticYuv) continue;

        pthread_mutex_lock(&mutex_);
        for (int i = 0; i < kMaxTargets; ++i) {
            if (targets_[i].active && targets_[i].window) snapshots[count++] = targets_[i];
        }
        pthread_mutex_unlock(&mutex_);

        // Align display-upright media with the camera sensor layout once, so the
        // same orientation feeds both the preview targets and the YUV readback.
        int renderSensor = alignSensorOrientation(sensorOri, frameW, frameH, frameRot);
        if (renderSensor != lastRenderSensor_) {
            lastRenderSensor_ = renderSensor;
            logMessage(logEnabled_, ANDROID_LOG_INFO,
                       "[Camera3] rotation: api=%d sensor=%d media=%dx%d rot=%d -> render=%d", api,
                       sensorOri, frameW, frameH, frameRot, renderSensor);
        }

        bool haveFrame = false;
        if (newFrame && surfaceTexture) {
            eglMakeCurrent(display_, pbuffer_, pbuffer_, context_);
            if (ASurfaceTexture_updateTexImage(surfaceTexture) == 0) {
                ASurfaceTexture_getTransformMatrix(surfaceTexture, lastStMatrix_);
                haveStMatrix_ = true;
                haveFrame = true;
            } else {
                logMessage(logEnabled_, ANDROID_LOG_WARN, "[Camera3] updateTexImage failed");
            }
        }

        if (haveFrame) {
            ensureTargetSurfaces();
            if (frameW > 0 && frameH > 0) {
                renderPreviews(snapshots, count, api, facingFront, renderSensor, displayOri,
                               processName, frameW, frameH, lastStMatrix_);
            }
        }

        bool canRenderYuv = (haveFrame || demandPending) && haveStMatrix_ && yuvW > 0 && yuvH > 0 &&
                            frameW > 0 && frameH > 0;
        if (canRenderYuv) {
            renderYuv(yuvW, yuvH, renderSensor, frameW, frameH, lastStMatrix_);
        } else if (demandPending) {
            // Nothing to render yet: acknowledge the demand so the loop can go
            // back to sleep and waiters fail with their timeout instead of
            // spinning here.
            pthread_mutex_lock(&mutex_);
            yuvPublished_ = yuvDemand_;
            pthread_mutex_unlock(&mutex_);
        }
    }

    pthread_mutex_lock(&mutex_);
    free(yuvBuffer_);
    yuvBuffer_ = nullptr;
    yuvBufferW_ = yuvBufferH_ = 0;
    if (pendingReleaseTexture_) ASurfaceTexture_release(pendingReleaseTexture_);
    if (surfaceTexture_) ASurfaceTexture_release(surfaceTexture_);
    pendingReleaseTexture_ = nullptr;
    surfaceTexture_ = nullptr;
    pthread_mutex_unlock(&mutex_);
}

}  // namespace camera3
