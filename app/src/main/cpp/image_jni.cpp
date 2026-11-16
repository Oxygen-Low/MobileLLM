// JNI bridge for the on-device image engine.
//
// A downloaded SD checkpoint is loaded into stable-diffusion.cpp and sampled
// with the prompt. The sampler returns interleaved RGB bytes; this file turns
// them into the raw RGBA the Kotlin PNG writer expects. The native library is
// shared with the text and video engines.

#include <jni.h>
#include <android/log.h>

#include "stable-diffusion.h"

#include <atomic>
#include <cstdint>
#include <cstring>
#include <string>
#include <vector>

namespace {

constexpr const char *TAG = "ImageBridge";

struct ImageEngine {
    std::string path;
    sd_ctx_t *ctx = nullptr;
    std::atomic<bool> cancelled{false};
};

ImageEngine *as_engine(jlong handle) {
    return reinterpret_cast<ImageEngine *>(handle);
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
Java_com_example_model_internal_ImageBridge_nativeLoadImageModel(
    JNIEnv *env, jobject, jstring path, jint nThreads) {
    const std::string model_path = read_jstring(env, path);
    if (model_path.empty()) return 0;

    sd_ctx_opt_t *options = sd_ctx_opt_create();
    if (options == nullptr) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "Could not allocate image sampler options");
        return 0;
    }

    bool ok = set_string(options, "model_path", model_path.c_str()) &&
              sd_opt_set_int(options, "n_threads", nThreads) == SD_STATUS_OK;

    sd_ctx_t *ctx = nullptr;
    if (ok) {
        const sd_status_t status = sd_ctx_create(options, &ctx);
        if (status != SD_STATUS_OK || ctx == nullptr) {
            log_sd_error("Could not load the image model");
            ok = false;
        }
    }
    sd_opt_free(options);
    if (!ok) {
        if (ctx != nullptr) sd_ctx_free(ctx);
        return 0;
    }

    try {
        ImageEngine *engine = new ImageEngine();
        engine->path = model_path;
        engine->ctx = ctx;
        __android_log_print(ANDROID_LOG_INFO, TAG, "Image model ready: %s", model_path.c_str());
        return reinterpret_cast<jlong>(engine);
    } catch (...) {
        sd_ctx_free(ctx);
        return 0;
    }
}

JNIEXPORT void JNICALL
Java_com_example_model_internal_ImageBridge_nativeFreeImageModel(JNIEnv *, jobject, jlong handle) {
    ImageEngine *engine = as_engine(handle);
    if (engine != nullptr) {
        if (engine->ctx != nullptr) sd_ctx_free(engine->ctx);
        delete engine;
    }
}

JNIEXPORT void JNICALL
Java_com_example_model_internal_ImageBridge_nativeCancelImageGeneration(
    JNIEnv *, jobject, jlong handle) {
    ImageEngine *engine = as_engine(handle);
    if (engine != nullptr && engine->ctx != nullptr) {
        engine->cancelled.store(true);
        sd_cancel_generation(engine->ctx, SD_CANCEL_ALL);
    }
}

JNIEXPORT jbyteArray JNICALL
Java_com_example_model_internal_ImageBridge_nativeGenerateImage(
    JNIEnv *env, jobject, jlong handle, jstring prompt, jint width, jint height,
    jint steps, jint seed, jfloat, jfloat, jfloat) {
    ImageEngine *engine = as_engine(handle);
    if (engine == nullptr || engine->ctx == nullptr) return nullptr;
    if (width <= 0 || height <= 0) return nullptr;

    // Latent diffusion needs dimensions divisible by eight. The Kotlin parser
    // aligns them, but keep the guard here since this is a public JNI surface.
    width = (width + 7) & ~7;
    height = (height + 7) & ~7;
    if (steps <= 0) steps = 1;
    if (steps > 50) steps = 50;

    const std::string text = read_jstring(env, prompt);
    if (text.empty()) return nullptr;

    engine->cancelled.store(false);

    sd_img_gen_opt_t *options = sd_img_gen_opt_create();
    if (options == nullptr) return nullptr;

    bool ok = set_string(options, "prompt", text.c_str()) &&
              set_string(options, "negative_prompt", "blurry, low quality, watermark, text, deformed") &&
              sd_opt_set_int(options, "width", width) == SD_STATUS_OK &&
              sd_opt_set_int(options, "height", height) == SD_STATUS_OK &&
              sd_opt_set_int(options, "seed", static_cast<int64_t>(seed)) == SD_STATUS_OK &&
              sd_opt_set_int(options, "sample.steps", steps) == SD_STATUS_OK &&
              set_string(options, "sample.method", "euler");

    // SD-Turbo is distilled: guidance has to stay low or the picture washes
    // out. The base 1.5 checkpoint wants ordinary classifier-free guidance.
    const bool distilled = engine->path.find("turbo") != std::string::npos;
    if (ok) {
        ok = sd_opt_set_float(options, "guidance.txt_cfg", distilled ? 1.0 : 7.0) == SD_STATUS_OK &&
             set_string(options, "sample.scheduler", distilled ? "simple" : "normal");
    }

    sd_result_t *result = nullptr;
    if (ok) {
        const sd_status_t status = sd_generate_image(engine->ctx, options, &result);
        if (status != SD_STATUS_OK || result == nullptr ||
            sd_result_get_image_count(result) == 0) {
            if (status != SD_STATUS_CANCELLED) log_sd_error("Image sampling failed");
            ok = false;
        }
    }
    sd_opt_free(options);
    if (!ok) {
        if (result != nullptr) sd_result_free(result);
        return nullptr;
    }

    const sd_tensor_t *image = sd_result_get_image(result, 0);
    const int channels = static_cast<int>(sd_tensor_dim(image, 0));
    const int image_width = static_cast<int>(sd_tensor_dim(image, 1));
    const int image_height = static_cast<int>(sd_tensor_dim(image, 2));
    const auto *pixels = static_cast<const uint8_t *>(sd_tensor_data(image));

    jbyteArray output = nullptr;
    if (channels >= 3 && pixels != nullptr && image_width == width && image_height == height) {
        const size_t count = static_cast<size_t>(image_width) * static_cast<size_t>(image_height);
        std::vector<uint8_t> rgba(count * 4u);
        for (size_t i = 0; i < count; ++i) {
            rgba[i * 4u + 0] = pixels[i * static_cast<size_t>(channels) + 0];
            rgba[i * 4u + 1] = pixels[i * static_cast<size_t>(channels) + 1];
            rgba[i * 4u + 2] = pixels[i * static_cast<size_t>(channels) + 2];
            rgba[i * 4u + 3] = 255;
        }
        output = env->NewByteArray(static_cast<jsize>(rgba.size()));
        if (output != nullptr) {
            env->SetByteArrayRegion(output, 0, static_cast<jsize>(rgba.size()),
                                    reinterpret_cast<const jbyte *>(rgba.data()));
        }
    } else {
        __android_log_print(ANDROID_LOG_ERROR, TAG,
                            "Unexpected image tensor: %dx%d, %d channels",
                            image_width, image_height, channels);
    }

    sd_result_free(result);
    if (engine->cancelled.load()) {
        if (output != nullptr) env->DeleteLocalRef(output);
        return nullptr;
    }
    return output;
}

} // extern "C"
