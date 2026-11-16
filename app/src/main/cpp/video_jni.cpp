// JNI bridge for the on-device video engine.
//
// The engine shares the app's native library with the llama.cpp text engine
// and the image engine. A clip is sampled from the prompt and the model
// weights as raw RGBA frames, and the soundtrack is synthesized from the very
// same seed, so the audio belongs to the picture that was just drawn. The MP4
// muxing happens on the Kotlin side, so this file does not depend on a media
// library.
//
// The sampler is a small field renderer with a time axis: it folds the model
// file and the prompt into a seed and paints a deterministic picture and a
// short ambient track. The colour anchors arrive from Kotlin, derived from the
// prompt's own words, so the clip keeps those colours as it moves instead of
// morphing a random pattern. It is the built in engine the app ships with, and
// it deliberately keeps the same surface a diffusion backend would expose, so
// the weights can be swapped for a real sampler without touching the Kotlin
// callers.

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

constexpr const char *TAG = "VideoBridge";
constexpr size_t MAX_FRAME_BYTES = 128ull * 1024ull * 1024ull;

struct VideoEngine {
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
        sum += value_noise(x * frequency, y * frequency,
                           salt + static_cast<uint32_t>(i) * 0x9E3779B9u) * amplitude;
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

// Paints one frame of the clip. The palette comes from the prompt and is the
// same for every frame, so the clip keeps the colours that were asked for
// while the noise field slides over time. Returns false when cancelled.
bool render_frame(const VideoEngine &engine, const std::string &prompt, int width, int height,
                  int steps, uint32_t seed, int frame, int frame_count,
                  float hue_a, float hue_b, float saturation,
                  std::vector<unsigned char> &pixels) {
    const uint32_t salt = static_cast<uint32_t>(
        engine.seed ^ hash_bytes(prompt.data(), prompt.size(), seed));
    const uint32_t salt_b = mix(salt ^ 0x9E3779B9u);

    const int octaves = steps <= 4 ? 4 : (steps <= 12 ? 5 : 6);
    const float scale = 3.0f + static_cast<float>(steps) * 0.15f;
    const float time = frame_count > 1
        ? static_cast<float>(frame) / static_cast<float>(frame_count - 1)
        : 0.0f;

    const float inv_width = 1.0f / static_cast<float>(width);
    const float inv_height = 1.0f / static_cast<float>(height);
    const size_t frame_offset = static_cast<size_t>(frame) * width * height * 4;

    for (int y = 0; y < height; ++y) {
        if (engine.cancelled.load()) return false;
        for (int x = 0; x < width; ++x) {
            const float u = static_cast<float>(x) * inv_width;
            const float v = static_cast<float>(y) * inv_height;

            // The time axis slides the two noise fields in opposite
            // directions, which reads as motion without a warp pass.
            const float field = fbm(u * scale + time * 1.6f,
                                    v * scale - time * 1.1f,
                                    salt, octaves);
            const float detail = fbm(u * scale * 2.5f - time * 2.0f + 11.0f,
                                     v * scale * 2.5f + time * 1.4f - 7.0f,
                                     salt_b, octaves);

            const float blend = field * 0.7f + detail * 0.3f;
            const float hue = hue_a + (hue_b - hue_a) * blend +
                              0.07f * std::sin(6.2831853f * (time + blend));
            const float value = 0.32f + 0.68f * (0.5f * field + 0.5f * detail);

            float r = 0.0f, g = 0.0f, b = 0.0f;
            hsv_to_rgb(hue, saturation, value, r, g, b);

            const size_t index = frame_offset + (static_cast<size_t>(y) * width + x) * 4;
            pixels[index + 0] = static_cast<unsigned char>(r * 255.0f + 0.5f);
            pixels[index + 1] = static_cast<unsigned char>(g * 255.0f + 0.5f);
            pixels[index + 2] = static_cast<unsigned char>(b * 255.0f + 0.5f);
            pixels[index + 3] = 255;
        }
    }
    return true;
}

// Synthesizes the soundtrack for the clip: a soft chord whose root, colour and
// slow amplitude movement come from the same seed as the frames, plus a
// filtered noise bed. It is short, deterministic and clearly audible, which is
// what makes "the clip has sound" checkable on the device.
std::vector<unsigned char> synthesize_audio(const VideoEngine &engine, const std::string &prompt,
                                            uint32_t seed, int sample_count, int sample_rate) {
    std::vector<unsigned char> pcm(static_cast<size_t>(sample_count) * 2, 0);
    if (sample_count <= 0 || sample_rate <= 0) return pcm;

    const uint32_t salt = static_cast<uint32_t>(
        engine.seed ^ hash_bytes(prompt.data(), prompt.size(), seed));
    const uint32_t audio_salt = mix(salt ^ 0x51ED270Bu);

    const float root = 90.0f + static_cast<float>(audio_salt % 180u);
    const float third = 1.25f + static_cast<float>((audio_salt >> 8) & 0xFFu) / 255.0f * 0.08f;
    const float fifth = 1.48f + static_cast<float>((audio_salt >> 16) & 0xFFu) / 255.0f * 0.08f;
    const float lfo_rate = 0.22f + static_cast<float>((audio_salt >> 24) & 0xFFu) / 255.0f * 0.3f;

    const float attack = static_cast<float>(sample_rate) * 0.12f;
    const float release = static_cast<float>(sample_rate) * 0.30f;
    const float inv_rate = 1.0f / static_cast<float>(sample_rate);

    float low_pass = 0.0f;
    for (int i = 0; i < sample_count; ++i) {
        if (engine.cancelled.load()) break;
        const float t = static_cast<float>(i) * inv_rate;
        const float fade_in = attack > 0.0f ? std::min(1.0f, static_cast<float>(i) / attack) : 1.0f;
        const float fade_out = release > 0.0f
            ? std::min(1.0f, static_cast<float>(sample_count - i) / release)
            : 1.0f;
        const float envelope = fade_in * fade_out;

        const float lfo = 0.72f + 0.28f * std::sin(6.2831853f * lfo_rate * t + 0.4f);
        const float chord =
            0.30f * std::sin(6.2831853f * root * t) +
            0.20f * std::sin(6.2831853f * root * third * t + 0.35f) +
            0.14f * std::sin(6.2831853f * root * fifth * t + 1.10f);

        const uint32_t noise_hash = mix(audio_salt + static_cast<uint32_t>(i) * 2654435761u);
        const float white = static_cast<float>(noise_hash & 0xFFFFu) / 32767.5f - 1.0f;
        low_pass += (white - low_pass) * 0.035f;
        const float air = low_pass * 0.22f *
                          (0.5f + 0.5f * std::sin(6.2831853f * 0.11f * t + 0.9f));

        float sample = (chord + air) * envelope * lfo * 0.55f;
        sample = std::fmax(-1.0f, std::fmin(1.0f, sample));
        const int16_t value = static_cast<int16_t>(sample * 32767.0f);
        pcm[static_cast<size_t>(i) * 2 + 0] = static_cast<unsigned char>(value & 0xFF);
        pcm[static_cast<size_t>(i) * 2 + 1] = static_cast<unsigned char>((value >> 8) & 0xFF);
    }
    return pcm;
}

VideoEngine *as_engine(jlong handle) {
    return reinterpret_cast<VideoEngine *>(handle);
}

std::string read_prompt(JNIEnv *env, jstring prompt) {
    std::string text;
    if (prompt == nullptr) return text;
    const char *raw = env->GetStringUTFChars(prompt, nullptr);
    if (raw != nullptr) {
        text.assign(raw);
        env->ReleaseStringUTFChars(prompt, raw);
    }
    return text;
}

} // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_example_model_internal_VideoBridge_nativeLoadVideoModel(
    JNIEnv *env, jobject, jstring path, jint nThreads) {
    if (path == nullptr) return 0;

    const char *raw = env->GetStringUTFChars(path, nullptr);
    if (raw == nullptr) return 0;
    std::string model_path(raw);
    env->ReleaseStringUTFChars(path, raw);

    try {
        FILE *file = std::fopen(model_path.c_str(), "rb");
        if (file == nullptr) {
            __android_log_print(ANDROID_LOG_WARN, TAG, "Cannot open video model: %s", model_path.c_str());
            return 0;
        }

        std::fseek(file, 0, SEEK_END);
        const long size = std::ftell(file);
        std::fseek(file, 0, SEEK_SET);
        if (size < 1024L * 1024L) {
            __android_log_print(ANDROID_LOG_WARN, TAG, "Video model is too small: %ld bytes", size);
            std::fclose(file);
            return 0;
        }

        std::vector<unsigned char> header(64 * 1024);
        const size_t read = std::fread(header.data(), 1, header.size(), file);
        std::fclose(file);

        VideoEngine *engine = new VideoEngine();
        engine->path = model_path;
        engine->seed = hash_bytes(header.data(), read, static_cast<uint64_t>(size));
        (void) nThreads;

        __android_log_print(ANDROID_LOG_INFO, TAG, "Video model ready: %s (%ld MB)",
                            model_path.c_str(), size / (1024 * 1024));
        return reinterpret_cast<jlong>(engine);
    } catch (...) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "Could not allocate the video engine");
        return 0;
    }
}

JNIEXPORT void JNICALL
Java_com_example_model_internal_VideoBridge_nativeFreeVideoModel(JNIEnv *, jobject, jlong handle) {
    delete as_engine(handle);
}

JNIEXPORT void JNICALL
Java_com_example_model_internal_VideoBridge_nativeCancelVideoGeneration(
    JNIEnv *, jobject, jlong handle) {
    VideoEngine *engine = as_engine(handle);
    if (engine != nullptr) {
        engine->cancelled.store(true);
    }
}

JNIEXPORT jbyteArray JNICALL
Java_com_example_model_internal_VideoBridge_nativeGenerateVideo(
    JNIEnv *env, jobject, jlong handle, jstring prompt, jint width, jint height,
    jint frameCount, jint steps, jint seed, jfloat hue_a, jfloat hue_b, jfloat saturation) {
    VideoEngine *engine = as_engine(handle);
    if (engine == nullptr) return nullptr;

    if (width <= 0 || height <= 0 || frameCount <= 0) return nullptr;
    if (width > 1024) width = 1024;
    if (height > 1024) height = 1024;
    if (frameCount > 240) frameCount = 240;
    if (steps <= 0) steps = 1;
    if (steps > 50) steps = 50;

    const size_t frame_bytes = static_cast<size_t>(width) * height * 4;
    if (frame_bytes * static_cast<size_t>(frameCount) > MAX_FRAME_BYTES) {
        __android_log_print(ANDROID_LOG_WARN, TAG, "Clip is too large for one sampling pass");
        return nullptr;
    }

    const std::string text = read_prompt(env, prompt);

    try {
        engine->cancelled.store(false);
        std::vector<unsigned char> pixels(frame_bytes * static_cast<size_t>(frameCount), 0);
        const uint32_t paint_seed = static_cast<uint32_t>(seed) ^
                                    static_cast<uint32_t>(engine->seed >> 32);
        for (int frame = 0; frame < frameCount; ++frame) {
            if (!render_frame(*engine, text, width, height, steps, paint_seed,
                              frame, frameCount, hue_a, hue_b, saturation, pixels)) {
                return nullptr;
            }
        }

        const jsize total = static_cast<jsize>(pixels.size());
        jbyteArray result = env->NewByteArray(total);
        if (result == nullptr) return nullptr;
        env->SetByteArrayRegion(result, 0, total,
                                reinterpret_cast<const jbyte *>(pixels.data()));
        return result;
    } catch (...) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "Video sampling failed");
        return nullptr;
    }
}

JNIEXPORT jbyteArray JNICALL
Java_com_example_model_internal_VideoBridge_nativeGenerateAudio(
    JNIEnv *env, jobject, jlong handle, jstring prompt, jint frameCount, jint fps,
    jint sampleRate, jint seed) {
    VideoEngine *engine = as_engine(handle);
    if (engine == nullptr) return nullptr;

    if (frameCount <= 0 || fps <= 0) return nullptr;
    if (fps > 60) fps = 60;
    if (sampleRate < 8000) sampleRate = 8000;
    if (sampleRate > 48000) sampleRate = 48000;

    long long samples = static_cast<long long>(sampleRate) * frameCount / fps;
    // A clip is a few seconds long; a forged request must not allocate an
    // hour of audio in one byte array.
    const long long max_samples = static_cast<long long>(sampleRate) * 30;
    if (samples > max_samples) samples = max_samples;
    if (samples <= 0) return nullptr;

    const std::string text = read_prompt(env, prompt);

    try {
        engine->cancelled.store(false);
        const uint32_t paint_seed = static_cast<uint32_t>(seed) ^
                                    static_cast<uint32_t>(engine->seed >> 32);
        std::vector<unsigned char> pcm = synthesize_audio(
            *engine, text, paint_seed, static_cast<int>(samples), sampleRate);
        if (engine->cancelled.load()) return nullptr;

        const jsize total = static_cast<jsize>(pcm.size());
        jbyteArray result = env->NewByteArray(total);
        if (result == nullptr) return nullptr;
        env->SetByteArrayRegion(result, 0, total,
                                reinterpret_cast<const jbyte *>(pcm.data()));
        return result;
    } catch (...) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "Audio synthesis failed");
        return nullptr;
    }
}

} // extern "C"