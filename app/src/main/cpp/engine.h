// libcamera3 engine: replaces the closed-source render core of Camera2Magic.
//
// One singleton owns a dedicated render thread with its own EGL/GLES3 context.
// Kotlin feeds it a SurfaceTexture (the selected photo/video/stream rendered by
// Camera3.kt) and a list of ANativeWindow targets; the engine mirrors the last
// decoded frame into every registered target, rotated/cropped for the active
// camera and app, and produces YUV (NV21) readbacks on demand for the
// Camera1/Camera2 YUV and JPEG replacement paths.
//
// Threading model:
//   * `mutex_` guards every field below (except GL objects, which are only ever
//     touched by the render thread once `initialized_` is true).
//   * Consumers (app camera threads) request YUV frames and wait on `cond_`;
//     the render thread honours the request on its next wake, even when no new
//     producer frame is available, by re-rendering the still-current texture.
//   * `bufBusy_` marks the shared CPU readback buffer while the render thread is
//     writing it, so consumers copy it safely without the render thread having
//     to hold the lock across glReadPixels.

#pragma once

#include <jni.h>

#include <pthread.h>
#include <stdint.h>

#include "gl_abi.h"

struct ANativeWindow;
struct ASurfaceTexture;

namespace camera3 {

class Engine {
public:
    Engine();

    static Engine& get();

    // ---- lifecycle ------------------------------------------------------
    void configure();  // idempotent; starts the render thread

    // ---- JNI entry points (contract with NativeBridge.kt) ---------------
    void setLogEnabled(bool enabled);
    void updateCameraBaseData(int api, bool facingFront, int sensorOri, int displayOri,
                              const char* processName);
    void updateManualRotation(int rotation);
    void updateFrameInfo(int width, int height, int rotation);
    void updateAlgorithmSize(int width, int height);
    int createOESTexture();
    void setSurfaceTexture(JNIEnv* env, jobject surfaceTexture);
    void getSurfaceInfo(JNIEnv* env, jobject surface, int out[3]);
    void notifyFrameAvailable();
    void addRenderTarget(JNIEnv* env, jobject surface, int vW, int vH, int pW, int pH);
    void addRenderTarget(JNIEnv* env, jobject surface);
    void removeRenderTarget(JNIEnv* env, jobject surface);
    void clearTargets();
    void overwriteYuvBuffer(JNIEnv* env, jbyteArray dst, int width, int height);
    void overwriteYuvPlanes(JNIEnv* env, jobject yBuffer, int yRowStride, int yPixelStride,
                            jobject uBuffer, int uRowStride, int uPixelStride,
                            jobject vBuffer, int vRowStride, int vPixelStride);
    jbyteArray overwriteJpegBytes(JNIEnv* env, int quality);

private:
    static constexpr int kMaxTargets = 16;
    static constexpr int kMaxProcessName = 128;
    // How long a consumer waits for the render thread to produce a frame. The
    // producer runs at ~30 fps, so one frame interval plus slack is plenty;
    // on timeout the caller leaves/zeroes its buffer instead of blocking.
    static constexpr int kYuvWaitMs = 400;

    struct Target {
        ANativeWindow* window;  // owned reference
        EGLSurface egl;         // owned by the render thread
        bool active;
        int vW, vH;             // Camera1 preview size
        int pW, pH;             // Camera1 picture size
    };

    Engine(const Engine&) = delete;
    Engine& operator=(const Engine&) = delete;

    // render thread
    static void* threadTrampoline(void* arg);
    void renderLoop();
    bool initEGL();
    bool initGL();
    void ensureTargetSurfaces();
    void cleanupTargets();
    void renderPreviews(const Target* targets, int count, int api, bool facingFront, int sensorOri,
                        int displayOri, const char* processName, int frameW, int frameH,
                        const float stMatrix[16]);
    // Renders + reads back the current frame as NV21 into the shared buffer.
    // Must be called with the GL context current on the render thread.
    void renderYuv(int width, int height, int sensorOri, int frameW, int frameH,
                   const float stMatrix[16]);

    // helpers (call with mutex_ held unless noted)
    int findTarget(ANativeWindow* window) const;
    bool ensureYuvLocked(int width, int height, uint8_t** outData);
    void reportInitLocked();
    int pictureWidthLocked() const;
    int pictureHeightLocked() const;

    // ---- shared state (mutex_) -----------------------------------------
    pthread_mutex_t mutex_;
    pthread_cond_t cond_;
    bool running_ = false;
    bool initialized_ = false;
    bool frameAvailable_ = false;
    bool cleanupPending_ = false;
    bool logEnabled_ = false;
    // 0 = render thread still starting, 1 = GL ready, -1 = init failed.
    // Reported once, and only once logging has been enabled by Kotlin, so an
    // early failure is still visible even though it happens before Kotlin has
    // had a chance to call setLogEnabled() (and nothing is printed while the
    // module log switch is off).
    int initState_ = 0;
    bool initReported_ = false;

    ASurfaceTexture* surfaceTexture_ = nullptr;
    ASurfaceTexture* pendingReleaseTexture_ = nullptr;

    int frameW_ = 0;
    int frameH_ = 0;
    int frameRot_ = 0;
    int api_ = 0;
    bool facingFront_ = false;
    int sensorOri_ = 0;
    int displayOri_ = 0;
    char processName_[kMaxProcessName] = {0};
    int manualRotation_ = 0;
    bool manualRotationSet_ = false;
    // Last sensor orientation handed to the renderer (media-aligned); only used
    // to keep the rotation diagnostic log from spamming every frame.
    int lastRenderSensor_ = -1;

    int algorithmW_ = 0;
    int algorithmH_ = 0;
    int autoYuvW_ = 0;
    int autoYuvH_ = 0;
    int requestYuvW_ = 0;
    int requestYuvH_ = 0;
    uint64_t yuvDemand_ = 0;
    uint64_t yuvPublished_ = 0;

    Target targets_[kMaxTargets]{};
    int targetCount_ = 0;

    uint8_t* yuvBuffer_ = nullptr;
    int yuvBufferW_ = 0;
    int yuvBufferH_ = 0;
    bool yuvReady_ = false;
    bool bufBusy_ = false;

    // ---- render thread only ---------------------------------------------
    EGLDisplay display_ = nullptr;
    EGLConfig config_ = nullptr;
    EGLContext context_ = nullptr;
    EGLSurface pbuffer_ = nullptr;
    GLuint oesTexture_ = 0;
    GLuint previewProgram_ = 0;
    GLuint yuvProgram_ = 0;
    GLint previewTexLoc_ = -1;
    GLint previewFixLoc_ = -1;
    GLint previewStLoc_ = -1;
    GLint yuvTexLoc_ = -1;
    GLint yuvFixLoc_ = -1;
    GLint yuvStLoc_ = -1;
    GLint yuvOffsetLoc_ = -1;
    GLint yuvNv12Loc_ = -1;
    GLuint previewVao_ = 0;
    GLuint previewVbo_ = 0;
    GLuint yuvVao_ = 0;
    GLuint yuvVbo_ = 0;
    GLuint yuvFbo_ = 0;
    GLuint yuvFboTexture_ = 0;
    int yuvFboW_ = 0;
    int yuvFboH_ = 0;  // includes the chroma rows
    float lastStMatrix_[16]{1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
    bool haveStMatrix_ = false;
};

}  // namespace camera3
