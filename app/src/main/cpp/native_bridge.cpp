// JNI registration for the rewritten libcamera3.
//
// The method table is registered dynamically (exactly like the original closed
// source library) instead of relying on Java_* symbol names, keeping the Kotlin
// contract in NativeBridge.kt explicit.

#include <jni.h>

#include <android/log.h>

#include "engine.h"

using camera3::Engine;

namespace {

void nativeSetLogEnabled(JNIEnv*, jobject, jboolean enabled) {
    Engine::get().setLogEnabled(enabled == JNI_TRUE);
}

void nativeUpdateCameraBaseData(JNIEnv* env, jobject, jint api, jboolean facingFront,
                                jint sensorOri, jint displayOri, jstring processName) {
    const char* name = nullptr;
    if (processName) name = env->GetStringUTFChars(processName, nullptr);
    Engine::get().updateCameraBaseData(api, facingFront == JNI_TRUE, sensorOri, displayOri, name);
    if (name) env->ReleaseStringUTFChars(processName, name);
}

void nativeUpdateManualRotation(JNIEnv*, jobject, jint rotation) {
    Engine::get().updateManualRotation(rotation);
}

void nativeUpdateFrameInfo(JNIEnv*, jobject, jint width, jint height, jint rotation) {
    Engine::get().updateFrameInfo(width, height, rotation);
}

void nativeUpdateAlgorithmSize(JNIEnv*, jobject, jint width, jint height) {
    Engine::get().updateAlgorithmSize(width, height);
}

jint nativeCreateOESTexture(JNIEnv*, jobject) {
    return Engine::get().createOESTexture();
}

void nativeSetSurfaceTexture(JNIEnv* env, jobject, jobject surfaceTexture) {
    Engine::get().setSurfaceTexture(env, surfaceTexture);
}

jintArray nativeGetSurfaceInfo(JNIEnv* env, jobject, jobject surface) {
    int info[3] = {0, 0, 0};
    Engine::get().getSurfaceInfo(env, surface, info);
    jintArray result = env->NewIntArray(3);
    if (result) {
        jint values[3] = {info[0], info[1], info[2]};
        env->SetIntArrayRegion(result, 0, 3, values);
    }
    return result;
}

void nativeNotifyFrameAvailable(JNIEnv*, jobject) {
    Engine::get().notifyFrameAvailable();
}

void nativeAddRenderTargetFull(JNIEnv* env, jobject, jobject surface, jint vWidth, jint vHeight,
                               jint pWidth, jint pHeight) {
    Engine::get().addRenderTarget(env, surface, vWidth, vHeight, pWidth, pHeight);
}

void nativeAddRenderTarget(JNIEnv* env, jobject, jobject surface) {
    Engine::get().addRenderTarget(env, surface);
}

void nativeRemoveRenderTarget(JNIEnv* env, jobject, jobject surface) {
    Engine::get().removeRenderTarget(env, surface);
}

void nativeClearTargets(JNIEnv*, jobject) {
    Engine::get().clearTargets();
}

void nativeOverwriteYuvBuffer(JNIEnv* env, jobject, jbyteArray buffer, jint width, jint height) {
    Engine::get().overwriteYuvBuffer(env, buffer, width, height);
}

void nativeOverwriteYuvPlanes(JNIEnv* env, jobject, jobject yBuffer, jint yRowStride,
                              jint yPixelStride, jobject uBuffer, jint uRowStride, jint uPixelStride,
                              jobject vBuffer, jint vRowStride, jint vPixelStride) {
    Engine::get().overwriteYuvPlanes(env, yBuffer, yRowStride, yPixelStride, uBuffer, uRowStride,
                                     uPixelStride, vBuffer, vRowStride, vPixelStride);
}

jbyteArray nativeOverwriteJpegBytes(JNIEnv* env, jobject, jint quality) {
    return Engine::get().overwriteJpegBytes(env, quality);
}

const JNINativeMethod kNativeMethods[] = {
    {"setLogEnabled", "(Z)V", reinterpret_cast<void*>(nativeSetLogEnabled)},
    {"updateCameraBaseData", "(IZIILjava/lang/String;)V",
     reinterpret_cast<void*>(nativeUpdateCameraBaseData)},
    {"updateManualRotation", "(I)V", reinterpret_cast<void*>(nativeUpdateManualRotation)},
    {"updateFrameInfo", "(III)V", reinterpret_cast<void*>(nativeUpdateFrameInfo)},
    {"updateAlgorithmSize", "(II)V", reinterpret_cast<void*>(nativeUpdateAlgorithmSize)},
    {"createOESTexture", "()I", reinterpret_cast<void*>(nativeCreateOESTexture)},
    {"setSurfaceTexture", "(Landroid/graphics/SurfaceTexture;)V",
     reinterpret_cast<void*>(nativeSetSurfaceTexture)},
    {"getSurfaceInfo", "(Landroid/view/Surface;)[I", reinterpret_cast<void*>(nativeGetSurfaceInfo)},
    {"notifyFrameAvailable", "()V", reinterpret_cast<void*>(nativeNotifyFrameAvailable)},
    {"addRenderTarget", "(Landroid/view/Surface;IIII)V",
     reinterpret_cast<void*>(nativeAddRenderTargetFull)},
    {"addRenderTarget", "(Landroid/view/Surface;)V", reinterpret_cast<void*>(nativeAddRenderTarget)},
    {"removeRenderTarget", "(Landroid/view/Surface;)V",
     reinterpret_cast<void*>(nativeRemoveRenderTarget)},
    {"clearTargets", "()V", reinterpret_cast<void*>(nativeClearTargets)},
    {"overwriteYuvBuffer", "([BII)V", reinterpret_cast<void*>(nativeOverwriteYuvBuffer)},
    {"overwriteYuvBuffer",
     "(Ljava/nio/ByteBuffer;IILjava/nio/ByteBuffer;IILjava/nio/ByteBuffer;II)V",
     reinterpret_cast<void*>(nativeOverwriteYuvPlanes)},
    {"overwriteJPEGBytes", "(I)[B", reinterpret_cast<void*>(nativeOverwriteJpegBytes)},
};

}  // namespace

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
    JNIEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        return JNI_ERR;
    }

    // Start the render thread as early as possible: Kotlin may ask for the OES
    // texture immediately after System.loadLibrary().
    Engine::get().configure();

    jclass bridge = env->FindClass("com/nothing/camera2magic/hook/NativeBridge");
    if (bridge) {
        // The class may already be present when both LSPosed's native_init and
        // System.loadLibrary load this library; a failed second registration is
        // harmless because the first one already bound every method.
        env->RegisterNatives(bridge, kNativeMethods,
                             (jint)(sizeof(kNativeMethods) / sizeof(kNativeMethods[0])));
        env->DeleteLocalRef(bridge);
    }
    return JNI_VERSION_1_6;
}
