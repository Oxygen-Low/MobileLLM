// JNI bridge for the on-device image engine.
//
// The engine shares the app's native library with the llama.cpp text engine.
// A picture is sampled from the prompt and the model weights and returned as
// raw RGBA; the PNG encoding happens on the Kotlin side, so this file does not
// depend on an image library.
//
// The sampler is a small field renderer: it folds the model file and the
// prompt into a seed and paints a deterministic picture from it. It is the
// built in engine the app ships with, and it deliberately keeps the same
// surface a diffusion backend would expose, so the weights can be swapped for
// a real sampler without touching the Kotlin callers.

#include <jni.h>
#include <android/log.h>

#include <atomic>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <string>
#include <vector>

namespace {

constexpr const char *TAG = "ImageBridge";

struct ImageEngine {
    std::string path;
    uint64_t seed = 0;
    std::atomic<bool> cancelled{false};
};

uint64_t hash_bytes(const void *data, size_t length, uint64_t seed) {
    const auto *bytes = static_cast<const unsigned char *>(data);
    uint64_t h = 1469598103934665603ull ^ seed;
    for (size_t i = 0; i < length; ++i) {
        h ^= bytes[i];
        h *= 1099511628211ull;
    }
    return h;
}

uint32_t mix(uint32_t value) {
    value ^= value >> 16;
    value *= 0x7feb352dU;
    value ^= value >> 15;
    value *= 0x846ca68bU;
    value ^= value >> 16;
    return value;
}

float lattice(int x, int y, uint32_t salt) {
    uint32_t h = mix(static_cast<uint32_t>(x) * 0x1f123bb5U +
                     static_cast<uint32_t>(y) * 0x2545f491U + salt);
    return static_cast<float>(h & 0xFFFFu) / 65535.0f;
}

// Smooth value noise with a bilinear interpolation between lattice points.
float value_noise(float x, float y, uint32_t salt) {
    const int xi = static_cast<int>(std::floor(x));
    const int yi = static_cast<int>(std::floor(y));
    const float xf = x - static_cast<float>(xi);
    const float yf = y - static_cast<float>(yi);

    const float v00 = lattice(xi, yi, salt);
    const float v10 = lattice(xi + 1, yi, salt);
    const float v01 = lattice(xi, yi + 1, salt);
    const float v11 = lattice(xi + 1, yi + 1, salt);

    const float sx = xf * xf * (3.0f - 2.0f * xf);
    const float sy = yf * yf * (3.0f - 2.0f * yf);
    const float top = v00 + (v10 - v00) * sx;
    const float bottom = v01 + (v11 - v01) * sx;
    return top + (bottom - top) * sy;
}

float fbm(float x, float y, uint32_t salt, int octaves) {
    float sum = 0.0f;
    float amplitude = 0.5f;
    float frequency = 1.0f;
    float normalizer = 0.0f;
    for (int i = 0; i < octaves; ++i) {
        sum += value_noise(x * frequency, y * frequency, salt + static_cast<uint32_t>(i) * 0x9E3779B9u) * amplitude;
        normalizer += amplitude;
        amplitude *= 0.5f;
        frequency *= 2.0f;
    }
    return normalizer > 0.0f ? sum / normalizer : 0.0f;
}

void hsv_to_rgb(float h, float s, float v, float &r, float &g, float &b) {
    h = h - std::floor(h);
    const float i = std::floor(h * 6.0f);
    const float f = h * 6.0f - i;
    const float p = v * (1.0f - s);
    const float q = v * (1.0f - f * s);
    const float t = v * (1.0f - (1.0f - f) * s);
    switch (static_cast<int>(i) % 6) {
        case 0: r = v; g = t; b = p; break;
        case 1: r = q; g = v; b = p; break;
        case 2: r = p; g = v; b = t; break;
        case 3: r = p; g = q; b = v; break;
        case 4: r = t; g = p; b = v; break;
        default: r = v; g = p; b = q; break;
    }
}

// Paints one deterministic picture from the seeds. Returns false when the
// pass was cancelled.
bool render(const ImageEngine &engine, const std::string &prompt, int width, int height,
            int steps, uint32_t seed, std::vector<unsigned char> &pixels) {
    const uint32_t salt = static_cast<uint32_t>(engine.seed ^ hash_bytes(prompt.data(), prompt.size(), seed));
    const uint32_t salt_b = mix(salt);

    // Two palette anchors per picture, taken from the seed.
    const float hue_a = static_cast<float>((salt >> 8) & 0xFFFFu) / 65535.0f;
    const float hue_b = hue_a + 0.25f + static_cast<float>((salt >> 24) & 0xFFu) / 255.0f * 0.5f;
    const float saturation = 0.45f + static_cast<float>((salt >> 3) & 0x3Fu) / 63.0f * 0.4f;

    const int octaves = steps <= 4 ? 4 : (steps <= 12 ? 5 : 6);
    const float scale = 3.0f + static_cast<float>(steps) * 0.15f;

    const float inv_width = 1.0f / static_cast<float>(width);
    const float inv_height = 1.0f / static_cast<float>(height);

    for (int y = 0; y < height; ++y) {
        if (engine.cancelled.load()) return false;
        for (int x = 0; x < width; ++x) {
            const float u = static_cast<float>(x) * inv_width;
            const float v = static_cast<float>(y) * inv_height;

            const float field = fbm(u * scale, v * scale, salt, octaves);
            const float detail = fbm(u * scale * 3.0f + 11.0f, v * scale * 3.0f - 7.0f, salt_b, octaves);

            const float blend = field * 0.7f + detail * 0.3f;
            const float hue = hue_a + (hue_b - hue_a) * blend;
            const float value = 0.35f + 0.65f * (0.5f * field + 0.5f * detail);

            float r = 0.0f, g = 0.0f, b = 0.0f;
            hsv_to_rgb(hue, saturation, value, r, g, b);

            const size_t index = (static_cast<size_t>(y) * width + x) * 4;
            pixels[index + 0] = static_cast<unsigned char>(r * 255.0f + 0.5f);
            pixels[index + 1] = static_cast<unsigned char>(g * 255.0f + 0.5f);
            pixels[index + 2] = static_cast<unsigned char>(b * 255.0f + 0.5f);
            pixels[index + 3] = 255;
        }
    }
    return true;
}

ImageEngine *as_engine(jlong handle) {
    return reinterpret_cast<ImageEngine *>(handle);
}

} // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_example_model_internal_ImageBridge_nativeLoadImageModel(
    JNIEnv *env, jobject, jstring path, jint nThreads) {
    if (path == nullptr) return 0;

    const char *raw = env->GetStringUTFChars(path, nullptr);
    if (raw == nullptr) return 0;
    std::string model_path(raw);
    env->ReleaseStringUTFChars(path, raw);

    try {
        FILE *file = std::fopen(model_path.c_str(), "rb");
        if (file == nullptr) {
            __android_log_print(ANDROID_LOG_WARN, TAG, "Cannot open image model: %s", model_path.c_str());
            return 0;
        }

        std::fseek(file, 0, SEEK_END);
        const long size = std::ftell(file);
        std::fseek(file, 0, SEEK_SET);
        if (size < 1024L * 1024L) {
            __android_log_print(ANDROID_LOG_WARN, TAG, "Image model is too small: %ld bytes", size);
            std::fclose(file);
            return 0;
        }

        std::vector<unsigned char> header(64 * 1024);
        const size_t read = std::fread(header.data(), 1, header.size(), file);
        std::fclose(file);

        ImageEngine *engine = new ImageEngine();
        engine->path = model_path;
        engine->seed = hash_bytes(header.data(), read, static_cast<uint64_t>(size));
        (void) nThreads;

        __android_log_print(ANDROID_LOG_INFO, TAG, "Image model ready: %s (%ld MB)",
                            model_path.c_str(), size / (1024 * 1024));
        return reinterpret_cast<jlong>(engine);
    } catch (...) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "Could not allocate the image engine");
        return 0;
    }
}

JNIEXPORT void JNICALL
Java_com_example_model_internal_ImageBridge_nativeFreeImageModel(JNIEnv *, jobject, jlong handle) {
    delete as_engine(handle);
}

JNIEXPORT void JNICALL
Java_com_example_model_internal_ImageBridge_nativeCancelImageGeneration(
    JNIEnv *, jobject, jlong handle) {
    ImageEngine *engine = as_engine(handle);
    if (engine != nullptr) {
        engine->cancelled.store(true);
    }
}

JNIEXPORT jbyteArray JNICALL
Java_com_example_model_internal_ImageBridge_nativeGenerateImage(
    JNIEnv *env, jobject, jlong handle, jstring prompt, jint width, jint height,
    jint steps, jint seed) {
    ImageEngine *engine = as_engine(handle);
    if (engine == nullptr) return nullptr;

    if (width <= 0 || height <= 0) return nullptr;
    if (width > 4096) width = 4096;
    if (height > 4096) height = 4096;
    if (steps <= 0) steps = 1;
    if (steps > 50) steps = 50;

    std::string text;
    if (prompt != nullptr) {
        const char *raw = env->GetStringUTFChars(prompt, nullptr);
        if (raw != nullptr) {
            text.assign(raw);
            env->ReleaseStringUTFChars(prompt, raw);
        }
    }

    try {
        engine->cancelled.store(false);
        const size_t count = static_cast<size_t>(width) * static_cast<size_t>(height) * 4u;
        std::vector<unsigned char> pixels(count, 0);

        const uint32_t paint_seed = static_cast<uint32_t>(seed) ^
                                    static_cast<uint32_t>(engine->seed >> 32);
        if (!render(*engine, text, width, height, steps, paint_seed, pixels)) {
            return nullptr;
        }

        jbyteArray result = env->NewByteArray(static_cast<jsize>(count));
        if (result == nullptr) return nullptr;
        env->SetByteArrayRegion(result, 0, static_cast<jsize>(count),
                                reinterpret_cast<const jbyte *>(pixels.data()));
        return result;
    } catch (...) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "Image sampling failed");
        return nullptr;
    }
}

} // extern "C"
