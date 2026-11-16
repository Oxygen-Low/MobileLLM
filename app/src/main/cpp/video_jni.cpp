// JNI bridge for the on-device video engine.
//
// Clips are sampled by stable-diffusion.cpp with an SD 1.5 checkpoint and an
// AnimateDiff motion module. That pair is far smaller than a full LTX video
// stack (which also needs a separate 12B text encoder and two VAEs), so it can
// actually fit on a phone. The soundtrack is generated separately on the
// Kotlin side with the on-device TTS model after these weights are released.
// Frames come back as interleaved RGBA for the existing MP4 encoder.

#include <jni.h>
#include <android/log.h>

#include "stable-diffusion.h"

#include <atomic>
#include <cstdint>
#include <cstring>
#include <string>
#include <vector>

namespace {

constexpr const char *TAG = "VideoBridge";
constexpr size_t MAX_FRAME_BYTES = 128ull * 1024ull * 1024ull;

struct VideoEngine {
    std::string path;
    std::string motion_path;
    sd_ctx_t *ctx = nullptr;
    std::atomic<bool> cancelled{false};
};

VideoEngine *as_engine(jlong handle) {
    return reinterpret_cast<VideoEngine *>(handle);
}

bool set_string(sd_opt_t *opt, const char *key, const char *value) {
    return value != nullptr && sd_opt_set_string(opt, key, value) == SD_STATUS_OK;
}

void log_sd_error(const char *what) {
    const char *message = sd_get_last_error();
    __android_log_print(ANDROID_LOG_ERROR, TAG, "%s: %s", what,
                        message != nullptr ? message : "no details");
}

std::string read_jstring(JNIEnv *env, jstring value) {
    if (value == nullptr) return std::string();
    const char *raw = env->GetStringUTFChars(value, nullptr);
    if (raw == nullptr) return std::string();
    std::string text(raw);
    env->ReleaseStringUTFChars(value, raw);
    return text;
}

} // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_example_model_internal_VideoBridge_nativeLoadVideoModel(
    JNIEnv *env, jobject, jstring path, jstring motionPath, jint nThreads) {
    const std::string model_path = read_jstring(env, path);
    const std::string motion = read_jstring(env, motionPath);
    if (model_path.empty() || motion.empty()) return 0;

    sd_ctx_opt_t *options = sd_ctx_opt_create();
    if (options == nullptr) return 0;

    bool ok = set_string(options, "model_path", model_path.c_str()) &&
              set_string(options, "motion_module_path", motion.c_str()) &&
              sd_opt_set_int(options, "n_threads", nThreads) == SD_STATUS_OK;

    sd_ctx_t *ctx = nullptr;
    if (ok) {
        const sd_status_t status = sd_ctx_create(options, &ctx);
        if (status != SD_STATUS_OK || ctx == nullptr) {
            log_sd_error("Could not load the video model");
            ok = false;
        }
    }
    sd_opt_free(options);
    if (!ok) {
        if (ctx != nullptr) sd_ctx_free(ctx);
        return 0;
    }

    try {
        VideoEngine *engine = new VideoEngine();
        engine->path = model_path;
        engine->motion_path = motion;
        engine->ctx = ctx;
        __android_log_print(ANDROID_LOG_INFO, TAG, "Video model ready: %s + %s",
                            model_path.c_str(), motion.c_str());
        return reinterpret_cast<jlong>(engine);
    } catch (...) {
        sd_ctx_free(ctx);
        return 0;
    }
}

JNIEXPORT void JNICALL
Java_com_example_model_internal_VideoBridge_nativeFreeVideoModel(JNIEnv *, jobject, jlong handle) {
    VideoEngine *engine = as_engine(handle);
    if (engine != nullptr) {
        if (engine->ctx != nullptr) sd_ctx_free(engine->ctx);
        delete engine;
    }
}

JNIEXPORT void JNICALL
Java_com_example_model_internal_VideoBridge_nativeCancelVideoGeneration(
    JNIEnv *, jobject, jlong handle) {
    VideoEngine *engine = as_engine(handle);
    if (engine != nullptr && engine->ctx != nullptr) {
        engine->cancelled.store(true);
        sd_cancel_generation(engine->ctx, SD_CANCEL_ALL);
    }
}

JNIEXPORT jbyteArray JNICALL
Java_com_example_model_internal_VideoBridge_nativeGenerateVideo(
    JNIEnv *env, jobject, jlong handle, jstring prompt, jint width, jint height,
    jint frameCount, jint steps, jint seed) {
    VideoEngine *engine = as_engine(handle);
    if (engine == nullptr || engine->ctx == nullptr) return nullptr;
    if (width <= 0 || height <= 0 || frameCount <= 0) return nullptr;

    width = (width + 7) & ~7;
    height = (height + 7) & ~7;
    if (width > 512) width = 512;
    if (height > 512) height = 512;
    if (frameCount > 32) frameCount = 32;
    if (steps <= 0) steps = 1;
    if (steps > 50) steps = 50;

    const size_t frame_bytes = static_cast<size_t>(width) * static_cast<size_t>(height) * 4u;
    if (frame_bytes * static_cast<size_t>(frameCount) > MAX_FRAME_BYTES) return nullptr;

    const std::string text = read_jstring(env, prompt);
    if (text.empty()) return nullptr;

    engine->cancelled.store(false);

    sd_vid_gen_opt_t *options = sd_vid_gen_opt_create();
    if (options == nullptr) return nullptr;

    bool ok = set_string(options, "prompt", text.c_str()) &&
              set_string(options, "negative_prompt", "blurry, low quality, watermark, text, deformed") &&
              sd_opt_set_int(options, "width", width) == SD_STATUS_OK &&
              sd_opt_set_int(options, "height", height) == SD_STATUS_OK &&
              sd_opt_set_int(options, "video_frames", frameCount) == SD_STATUS_OK &&
              sd_opt_set_int(options, "seed", static_cast<int64_t>(seed)) == SD_STATUS_OK &&
              sd_opt_set_int(options, "sample.steps", steps) == SD_STATUS_OK &&
              set_string(options, "sample.method", "euler") &&
              set_string(options, "sample.scheduler", "discrete") &&
              sd_opt_set_float(options, "guidance.txt_cfg", 8.0) == SD_STATUS_OK;

    sd_result_t *result = nullptr;
    if (ok) {
        const sd_status_t status = sd_generate_video(engine->ctx, options, &result);
        if (status != SD_STATUS_OK || result == nullptr ||
            sd_result_get_image_count(result) == 0) {
            if (status != SD_STATUS_CANCELLED) log_sd_error("Video sampling failed");
            ok = false;
        }
    }
    sd_opt_free(options);
    if (!ok) {
        if (result != nullptr) sd_result_free(result);
        return nullptr;
    }

    const size_t count = sd_result_get_image_count(result);
    std::vector<uint8_t> frames(frame_bytes * static_cast<size_t>(frameCount), 0);
    const size_t frames_to_copy = count < static_cast<size_t>(frameCount)
        ? count : static_cast<size_t>(frameCount);

    for (size_t frame = 0; frame < frames_to_copy; ++frame) {
        if (engine->cancelled.load()) break;
        const sd_tensor_t *image = sd_result_get_image(result, frame);
        if (image == nullptr) continue;
        const int channels = static_cast<int>(sd_tensor_dim(image, 0));
        const int image_width = static_cast<int>(sd_tensor_dim(image, 1));
        const int image_height = static_cast<int>(sd_tensor_dim(image, 2));
        const auto *pixels = static_cast<const uint8_t *>(sd_tensor_data(image));
        if (channels < 3 || pixels == nullptr ||
            image_width != width || image_height != height) {
            __android_log_print(ANDROID_LOG_ERROR, TAG,
                                "Unexpected frame tensor: %dx%d, %d channels",
                                image_width, image_height, channels);
            continue;
        }
        uint8_t *out = frames.data() + frame * frame_bytes;
        const size_t pixel_count = static_cast<size_t>(width) * static_cast<size_t>(height);
        for (size_t i = 0; i < pixel_count; ++i) {
            out[i * 4u + 0] = pixels[i * static_cast<size_t>(channels) + 0];
            out[i * 4u + 1] = pixels[i * static_cast<size_t>(channels) + 1];
            out[i * 4u + 2] = pixels[i * static_cast<size_t>(channels) + 2];
            out[i * 4u + 3] = 255;
        }
    }

    sd_result_free(result);
    if (engine->cancelled.load() || frames_to_copy != static_cast<size_t>(frameCount)) {
        return nullptr;
    }

    const jsize total = static_cast<jsize>(frames.size());
    jbyteArray output = env->NewByteArray(total);
    if (output != nullptr) {
        env->SetByteArrayRegion(output, 0, total,
                                reinterpret_cast<const jbyte *>(frames.data()));
    }
    return output;
}

} // extern "C"
